package co.sirdab.printer

import android.util.Log
import co.sirdab.printer.models.PrintJob
import co.sirdab.printer.models.PrintResult
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject

/**
 * Embedded HTTP server (NanoHTTPD) that exposes the printer companion API
 * on localhost:8080.
 *
 * Routes:
 *   POST /print       — submit a print job
 *   GET  /status      — check printer status
 *   GET  /config      — read current configuration
 *   POST /config      — update configuration
 *   GET  /health      — basic liveness check (for Fully Kiosk watchdog scripts)
 *   OPTIONS *         — CORS preflight (required for fetch() from Fully Kiosk Browser)
 *
 * All responses are JSON.  Every response includes CORS headers so that
 * fetch('http://localhost:8080/...') from Fully Kiosk Browser works without
 * triggering a "blocked by CORS policy" error.
 *
 * NanoHTTPD spawns one thread per connection.  The /print handler blocks that
 * thread for the duration of the job (download + render + SDK).  This is fine
 * because the SDK is synchronous by design and the single-thread executor in
 * PrinterClient ensures only one job runs at a time regardless.
 */
class PrintHttpServer(
    private val activity: MainActivity
) : NanoHTTPD(PORT) {

    companion object {
        const val PORT = 8080
        private const val TAG = "PrintHttpServer"
        private const val MIME_JSON = "application/json"
    }

    private val printerClient get() = activity.printerClient
    private val configManager get() = activity.configManager

    // ── Route dispatch ────────────────────────────────────────────────────────

    override fun serve(session: IHTTPSession): Response {
        Log.d(TAG, "${session.method} ${session.uri}")

        val response: Response = try {
            when {
                session.method == Method.OPTIONS ->
                    corsPreflightResponse()

                session.method == Method.POST && session.uri == "/print" ->
                    handlePrint(session)

                session.method == Method.GET && session.uri == "/status" ->
                    handleStatus()

                session.method == Method.GET && session.uri == "/config" ->
                    handleGetConfig()

                session.method == Method.POST && session.uri == "/config" ->
                    handleSetConfig(session)

                session.method == Method.GET && session.uri == "/health" ->
                    handleHealth()

                else ->
                    errorResponse(404, "not_found",
                        "No route for ${session.method} ${session.uri}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Unhandled error in ${session.uri}", e)
            errorResponse(500, "internal_error", e.message ?: "Unexpected server error")
        }

        addCorsHeaders(response)
        return response
    }

    // ── Handlers ──────────────────────────────────────────────────────────────

    /**
     * POST /print
     *
     * Request body (JSON):
     *   pdfUrl      String  required   Full URL to the PDF
     *   printerIp   String  optional   Overrides stored config for this job
     *   printerPort Int     optional   Overrides stored config for this job
     *   copies      Int     optional   Number of copies per page (default 1, max 10)
     *
     * Success response 200:
     *   { "ok": true, "pages": 1 }
     *
     * Failure responses:
     *   400  missing/invalid request fields
     *   422  PDF could not be downloaded or rendered
     *   503  printer unreachable or hardware error
     *   504  job timed out
     *   500  unexpected SDK / server error
     */
    private fun handlePrint(session: IHTTPSession): Response {
        val body = parseJsonBody(session)
            ?: return errorResponse(400, "invalid_body",
                "Request body must be valid JSON with Content-Type: application/json")

        val pdfUrl = body.optString("pdfUrl").takeIf { it.isNotBlank() }
            ?: return errorResponse(400, "missing_pdfUrl",
                "Required field 'pdfUrl' is missing or empty")

        // Optional per-request overrides
        val printerIp   = body.optString("printerIp").takeIf { it.isNotBlank() }
        val printerPort = if (body.has("printerPort")) body.optInt("printerPort") else null
        val copies      = body.optInt("copies", 1).coerceIn(1, 10)

        val job = PrintJob(
            pdfUrl      = pdfUrl,
            printerIp   = printerIp,
            printerPort = printerPort,
            copies      = copies
        )

        // Inform the status UI
        activity.updateStatus("Printing…")

        return when (val result = printerClient.print(job)) {
            is PrintResult.Success -> {
                activity.updateStatus("Ready — last print OK (${result.pagesRendered}p)")
                activity.recordLastPrint(success = true, detail = "${result.pagesRendered} page(s)")
                jsonResponse(200, buildMap {
                    put("ok",    true)
                    put("pages", result.pagesRendered)
                })
            }

            is PrintResult.Failure -> {
                activity.updateStatus("Error: ${result.message}")
                activity.recordLastPrint(success = false, detail = result.message)
                jsonResponse(result.httpStatus, buildMap {
                    put("ok",     false)
                    put("error",  result.code)
                    put("detail", result.message)
                })
            }
        }
    }

    /**
     * GET /status
     *
     * Returns the current printer state without sending a print job.
     * The webapp can call this on page load to show a status badge to pickers.
     *
     * Response 200:
     *   {
     *     "ok": true,
     *     "configured": true,
     *     "printer": {
     *       "state": "ready",          // ready | printing | paused | out_of_paper |
     *                                  // out_of_ribbon | paper_jam | head_open |
     *                                  // unreachable | not_configured | error | unknown
     *       "description": "Ready",
     *       "raw": "00"
     *     }
     *   }
     */
    private fun handleStatus(): Response {
        val printerStatus = printerClient.getStatus()
        return jsonResponse(200, buildMap {
            put("ok",         true)
            put("configured", configManager.isConfigured())
            put("printer",    printerStatus)
        })
    }

    /**
     * GET /config
     *
     * Returns the current stored configuration.
     */
    private fun handleGetConfig(): Response {
        return jsonResponse(200, buildMap {
            put("ok", true)
            putAll(configManager.toMap())
        })
    }

    /**
     * POST /config
     *
     * Accepts a partial JSON object — only the supplied keys are updated.
     * Useful for scripted setup (e.g. a one-time curl command per tablet).
     *
     * Writable fields:
     *   printer_ip       String
     *   printer_port     Int
     *   label_width_mm   Int
     *   label_height_mm  Int
     *   gap_mm           Int    (1–10)
     *   print_speed      Int    (1–15)
     *   print_density    Int    (0–15)
     */
    private fun handleSetConfig(session: IHTTPSession): Response {
        val body = parseJsonBody(session)
            ?: return errorResponse(400, "invalid_body", "Expected JSON body")

        body.optString("printer_ip").takeIf    { it.isNotBlank()  }?.let { configManager.printerIp      = it }
        if (body.has("printer_port"))     configManager.printerPort     = body.getInt("printer_port")
        if (body.has("label_width_mm"))   configManager.labelWidthMm    = body.getInt("label_width_mm")
        if (body.has("label_height_mm"))  configManager.labelHeightMm   = body.getInt("label_height_mm")
        if (body.has("gap_mm"))           configManager.gapMm           = body.getInt("gap_mm").coerceIn(1, 10)
        if (body.has("print_speed"))      configManager.printSpeed      = body.getInt("print_speed")
        if (body.has("print_density"))    configManager.printDensity    = body.getInt("print_density")

        activity.refreshConfigDisplay()
        Log.i(TAG, "Config updated: ${configManager.toMap()}")

        return jsonResponse(200, buildMap {
            put("ok", true)
            putAll(configManager.toMap())
        })
    }

    /**
     * GET /health
     *
     * Minimal liveness probe.  Returns immediately without touching the printer.
     * Use this in Fully Kiosk watchdog rules or a monitoring script.
     */
    private fun handleHealth(): Response {
        return jsonResponse(200, mapOf(
            "ok"      to true,
            "service" to "printer-companion",
            "version" to "1.0.0"
        ))
    }

    // ── CORS ──────────────────────────────────────────────────────────────────

    /**
     * Handles the OPTIONS preflight that browsers send before a cross-origin
     * POST.  fetch('http://localhost:8080/print') from Fully Kiosk Browser
     * originates from a different origin (the Rails app URL), so CORS applies.
     */
    private fun corsPreflightResponse(): Response {
        return newFixedLengthResponse(Response.Status.OK, MIME_JSON, "{\"ok\":true}")
    }

    private fun addCorsHeaders(response: Response) {
        response.addHeader("Access-Control-Allow-Origin",  "*")
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        response.addHeader("Access-Control-Allow-Headers", "Content-Type, Accept")
        response.addHeader("Access-Control-Max-Age",       "86400")
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Reads and parses the POST body as JSON.
     * NanoHTTPD requires calling parseBody() to buffer the request body —
     * the raw InputStream is not seekable.
     */
    private fun parseJsonBody(session: IHTTPSession): JSONObject? {
        return try {
            val files = HashMap<String, String>()
            session.parseBody(files)
            val raw = files["postData"] ?: return null
            JSONObject(raw)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse JSON body", e)
            null
        }
    }

    private fun jsonResponse(statusCode: Int, data: Map<String, Any?>): Response {
        val json = JSONObject(data).toString()
        return newFixedLengthResponse(httpStatus(statusCode), MIME_JSON, json)
    }

    private fun errorResponse(statusCode: Int, code: String, detail: String): Response {
        return jsonResponse(statusCode, mapOf(
            "ok"     to false,
            "error"  to code,
            "detail" to detail
        ))
    }

    /**
     * Maps an integer HTTP status code to a NanoHTTPD IStatus.
     *
     * NanoHTTPD 2.3.1's Response.Status enum does not include every RFC status
     * code (notably 422 and 504), so we create anonymous IStatus objects for
     * those rather than relying on enum valueOf() which would throw at runtime.
     */
    private fun httpStatus(code: Int): Response.IStatus = when (code) {
        200  -> Response.Status.OK
        400  -> Response.Status.BAD_REQUEST
        404  -> Response.Status.NOT_FOUND
        500  -> Response.Status.INTERNAL_ERROR
        503  -> Response.Status.SERVICE_UNAVAILABLE
        else -> object : Response.IStatus {
            override fun getDescription() = "$code ${descriptionFor(code)}"
            override fun getRequestStatus() = code
        }
    }

    private fun descriptionFor(code: Int): String = when (code) {
        422  -> "Unprocessable Entity"
        504  -> "Gateway Timeout"
        else -> "Unknown"
    }
}
