package co.sirdab.printer

import android.content.Context
import android.util.Log
import co.sirdab.printer.models.PrintJob
import co.sirdab.printer.models.PrintResult
import com.gainscha.gtspl_sdk.GTSPLWIFIActivity
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Owns all interaction with the GAINSCHA SDK.
 *
 * Design decisions:
 *  - [sdk] is the MainActivity instance, which extends GTSPLWIFIActivity.
 *    The SDK methods are called on that object; every call takes a Context param
 *    which we pass as [context] (ApplicationContext — avoids Activity memory leaks).
 *  - A **single-thread executor** serialises all print jobs.  Concurrent requests
 *    from the webapp queue up and are processed in order — the printer can only
 *    handle one job at a time anyway.
 *  - All SDK calls are synchronous blocking I/O.  They must never run on the
 *    Android main thread.  The executor thread satisfies this requirement.
 *  - The caller (NanoHTTPD handler thread) blocks on future.get() until the job
 *    completes or times out.  This is intentional: the HTTP response contains the
 *    print result, so the webapp always gets definitive success/failure.
 *
 * Printer status codes (returned by GTSPL_printersStatus):
 *   00 Normal · 01 Head open · 02 Paper jam · 04 Out of paper
 *   08 Out of ribbon · 10 Paused · 20 Printing · 80 Other error
 */
class PrinterClient(
    private val sdk: GTSPLWIFIActivity,
    private val context: Context,
    private val config: ConfigManager,
    private val pdfRenderer: PdfPageRenderer,
    private val webRenderer: WebPageRenderer = WebPageRenderer(context)
) {
    companion object {
        private const val TAG = "PrinterClient"

        /** Timeout for an entire print job (download + render + SDK calls). */
        private const val JOB_TIMEOUT_SECONDS = 90L

        /** Delay between pages so the printer buffer doesn't overflow. */
        private const val INTER_PAGE_DELAY_MS = 400L

        /** How long to wait for a status poll after the last printLabel call. */
        private const val STATUS_POLL_DELAY_MS = 800

        /** Number of connection attempts before giving up on a print job. */
        private const val CONNECT_MAX_RETRIES = 3

        /** Delay between connection retries — long enough for WiFi to recover. */
        private const val CONNECT_RETRY_DELAY_MS = 1_500L
    }

    // Single-thread executor: one print job at a time
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "printer-worker").apply { isDaemon = true }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Submits [job] to the print queue and **blocks** until it completes.
     * Safe to call from any thread (NanoHTTPD, test code, etc.).
     */
    fun print(job: PrintJob): PrintResult {
        val future: Future<PrintResult> = executor.submit<PrintResult> {
            executePrintJob(job)
        }
        return try {
            future.get(JOB_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            PrintResult.Failure("job_timeout",
                "Print job exceeded ${JOB_TIMEOUT_SECONDS}s timeout", 504)
        } catch (e: Exception) {
            PrintResult.Failure("internal_error", e.cause?.message ?: e.message ?: "Unknown error")
        }
    }

    /**
     * Opens a brief connection to the printer and returns a human-readable
     * status string.  Used by GET /status.
     */
    fun getStatus(): Map<String, Any> {
        if (!config.isConfigured()) {
            return mapOf("state" to "not_configured", "description" to "No printer IP set")
        }

        val future: Future<Map<String, Any>> = executor.submit<Map<String, Any>> {
            queryPrinterStatus()
        }
        return try {
            future.get(15, TimeUnit.SECONDS)
        } catch (e: Exception) {
            mapOf("state" to "error", "description" to (e.message ?: "Status check failed"))
        }
    }

    /** Shuts down the executor.  Call from MainActivity.onDestroy(). */
    fun shutdown() {
        executor.shutdown()
    }

    // ── Private: full print pipeline ──────────────────────────────────────────

    private fun executePrintJob(job: PrintJob): PrintResult {
        val ip   = job.printerIp   ?: config.printerIp
        val port = job.printerPort ?: config.printerPort

        if (ip.isEmpty()) {
            return PrintResult.Failure("no_printer_config",
                "Printer IP not configured. POST /config with {\"printer_ip\":\"...\"}", 503)
        }

        Log.i(TAG, "Job start → printer=$ip:$port url=${job.pdfUrl}")

        // ── Step 1: Download + render ─────────────────────────────────────────
        val jobId = System.currentTimeMillis().toString()

        // Sync label dimensions from config so the renderer scales to fit the physical label
        val dotsPerMm = PdfPageRenderer.PRINTER_DPI / 25.4f
        pdfRenderer.targetWidthDots  = (config.labelWidthMm  * dotsPerMm).toInt()
        pdfRenderer.targetHeightDots = (config.labelHeightMm * dotsPerMm).toInt()

        val pages = try {
            val result = pdfRenderer.downloadAndRender(job.pdfUrl, jobId)
            result
        } catch (e: PdfPageRenderer.HtmlContentException) {
            // URL returned HTML (e.g. OTO's web viewer) — fall back to WebView rendering
            Log.i(TAG, "PDF URL returned HTML, falling back to WebView renderer")
            try {
                val webPage = webRenderer.render(job.pdfUrl, jobId)
                listOf(PdfPageRenderer.RenderedPage(
                    filename   = webPage.filename,
                    file       = webPage.file,
                    widthPx    = webPage.widthPx,
                    widthBytes = webPage.widthBytes,
                    heightDots = webPage.heightDots
                ))
            } catch (webEx: Exception) {
                Log.e(TAG, "WebView render also failed", webEx)
                return PrintResult.Failure("render_error",
                    "Could not render label: ${webEx.message}", 422)
            }
        } catch (e: Exception) {
            Log.e(TAG, "PDF processing failed", e)
            return PrintResult.Failure("pdf_error",
                "PDF processing failed: ${e.message}", 422)
        }

        if (pages.isEmpty()) {
            return PrintResult.Failure("empty_pdf", "PDF contained no pages", 422)
        }

        Log.i(TAG, "Rendered ${pages.size} page(s) (job=$jobId)")

        // ── Step 2: Print via SDK ─────────────────────────────────────────────
        return try {
            sendToSdk(ip, port, job, pages)
        } finally {
            // Always clean up rendered PNG files, even on failure
            pages.forEach { page ->
                if (page.file.delete()) {
                    Log.d(TAG, "Cleaned up ${page.filename}")
                }
            }
        }
    }

    private fun sendToSdk(
        ip: String,
        port: Int,
        job: PrintJob,
        pages: List<PdfPageRenderer.RenderedPage>
    ): PrintResult {
        // ── Connect (with retries for transient WiFi failures) ────────────────
        sdk.GTSPL_setCmdSendMode("P")   // "P" = send to printer (not to file)

        var connected = false
        for (attempt in 1..CONNECT_MAX_RETRIES) {
            connected = sdk.GTSPL_openPort(ip, port, 5000)
            if (connected) break
            if (attempt < CONNECT_MAX_RETRIES) {
                Log.w(TAG, "Connect attempt $attempt/$CONNECT_MAX_RETRIES failed — " +
                    "retrying in ${CONNECT_RETRY_DELAY_MS}ms")
                Thread.sleep(CONNECT_RETRY_DELAY_MS)
            }
        }
        if (!connected) {
            return PrintResult.Failure("printer_unreachable",
                "Could not connect to printer at $ip:$port after $CONNECT_MAX_RETRIES attempts. " +
                "Verify the printer is powered on and on the same WiFi network.",
                503)
        }
        Log.i(TAG, "Connected to $ip:$port")

        return try {
            // ── Configure label ───────────────────────────────────────────────
            // Called on every job so config changes (via POST /config) take effect
            // without needing to restart the app.
            sdk.GTSPL_setup(
                config.labelWidthMm,   // label width  (mm)
                config.labelHeightMm,  // label height (mm)
                config.printSpeed,     // speed        (1–15 ips)
                config.printDensity,   // density      (0–15)
                0,                     // sensor type  (0 = gap sensor)
                config.gapMm,          // gap distance (mm)
                0,                     // gap offset   (mm)
                context
            )

            // ── Print each page ───────────────────────────────────────────────
            for ((index, page) in pages.withIndex()) {
                Log.d(TAG, "Printing page ${index + 1}/${pages.size}: ${page.filename} " +
                    "(${page.widthPx}px × ${page.heightDots}dots)")

                sdk.GTSPL_clearBuffer(context)

                // Position: top-left corner of label (0,0)
                // Width: pixel width of the bitmap (SDK sample uses 300 for a 300px image)
                // Mode 0 = OVERWRITE (correct for full-label bitmap)
                sdk.GTSPL_printBMP(
                    0,               // x offset in dots
                    0,               // y offset in dots
                    page.widthPx,    // pixel width  e.g. 812 for a 4" label at 203 DPI
                    page.heightDots, // height in dots  e.g. 1218
                    0,               // mode: OVERWRITE
                    page.filename,   // bare filename in getExternalFilesDir
                    context
                )

                sdk.GTSPL_printLabel(1, job.copies, context)

                // Give the printer time to process before sending the next page
                if (index < pages.size - 1) {
                    Thread.sleep(INTER_PAGE_DELAY_MS)
                }
            }

            // ── Final status check ────────────────────────────────────────────
            // Wait briefly for the printer to transition from "printing" (0x20) to
            // "normal" (0x00) before polling — otherwise we'd always see 0x20.
            Thread.sleep(STATUS_POLL_DELAY_MS.toLong())
            val statusCode = sdk.GTSPL_printersStatus(500)
            val error = statusCodeToError(statusCode)

            if (error != null) {
                Log.w(TAG, "Printer reported error after print: $statusCode ($error)")
                PrintResult.Failure("printer_error", error, 503)
            } else {
                Log.i(TAG, "Job complete — ${pages.size} page(s) printed")
                PrintResult.Success(pages.size)
            }

        } finally {
            sdk.GTSPL_closePort()
            Log.d(TAG, "Disconnected from $ip:$port")
        }
    }

    // ── Private: status query ─────────────────────────────────────────────────

    private fun queryPrinterStatus(): Map<String, Any> {
        val ip   = config.printerIp
        val port = config.printerPort

        sdk.GTSPL_setCmdSendMode("P")
        val connected = sdk.GTSPL_openPort(ip, port, 3000)
        if (!connected) {
            return mapOf("state" to "unreachable",
                "description" to "Could not connect to $ip:$port")
        }

        return try {
            val code = sdk.GTSPL_printersStatus(500)
            sdk.GTSPL_closePort()
            mapOf(
                "state"       to statusCodeToState(code),
                "description" to statusCodeToDescription(code),
                "raw"         to code
            )
        } catch (e: Exception) {
            sdk.GTSPL_closePort()
            mapOf("state" to "error", "description" to (e.message ?: "Unknown"))
        }
    }

    // ── Status helpers ────────────────────────────────────────────────────────

    /**
     * Returns a non-null error message if the status code indicates a problem
     * that should fail the print job.  Returns null for "normal" and "printing".
     *
     * Note: status 0x20 (printing) is not treated as an error here because
     * it can appear during the brief window before the job is fully spooled.
     */
    private fun statusCodeToError(code: String): String? = when (code.uppercase()) {
        "00", "20" -> null
        "01"       -> "Printer head is open — close the cover and retry"
        "02"       -> "Paper jam — clear the jam and retry"
        "03"       -> "Paper jam and head open — clear jam, close cover and retry"
        "04"       -> "Out of paper — reload label roll"
        "05"       -> "Out of paper and head open — reload labels and close cover"
        "08"       -> "Out of ribbon"
        "09"       -> "Out of ribbon and head open"
        "0A"       -> "Out of ribbon and paper jam"
        "0B"       -> "Out of ribbon, paper jam and head open"
        "0C"       -> "Out of ribbon and out of paper"
        "0D"       -> "Out of ribbon, out of paper and head open"
        "10"       -> "Printer is paused — press the feed button to resume"
        "80"       -> "Printer hardware error — power-cycle the printer"
        else       -> null  // Unknown status: don't fail the job, just log it
    }

    private fun statusCodeToState(code: String): String = when (code.uppercase()) {
        "00"       -> "ready"
        "20"       -> "printing"
        "10"       -> "paused"
        "04", "05" -> "out_of_paper"
        "08", "09",
        "0C", "0D" -> "out_of_ribbon"
        "02", "03",
        "0A", "0B" -> "paper_jam"
        "01"       -> "head_open"
        "80"       -> "error"
        else       -> "unknown"
    }

    private fun statusCodeToDescription(code: String): String =
        statusCodeToError(code) ?: when (code.uppercase()) {
            "00" -> "Ready"
            "20" -> "Printing"
            else -> "Unknown status ($code)"
        }
}
