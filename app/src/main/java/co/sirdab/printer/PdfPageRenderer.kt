package co.sirdab.printer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/**
 * Downloads a PDF from a URL and renders each page to a PNG file that the
 * GAINSCHA SDK can consume via GTSPL_printBMP().
 *
 * Key facts about the render pipeline:
 *  - Target DPI is 203 (GS-2406T native resolution)
 *  - PDF coordinates are in points (1 pt = 1/72 inch)
 *  - Scale factor = 203 / 72 ≈ 2.819
 *  - A 4"×6" label PDF (288×432 pt) renders to 812×1218 px
 *  - SDK width param is in BYTES (1 byte = 8 horizontal dots): ceil(812/8) = 102
 *  - Output files are saved to getExternalFilesDir(""), the only path the SDK accepts
 */
class PdfPageRenderer(private val context: Context) {

    companion object {
        private const val TAG = "PdfPageRenderer"

        /** Native DPI of the GS-2406T print head. */
        const val PRINTER_DPI = 203

        /** Points-to-dots scale factor. */
        val PDF_TO_DOT_SCALE: Float = PRINTER_DPI.toFloat() / 72f

        /** Connection timeout when downloading the PDF. */
        private const val CONNECT_TIMEOUT_MS = 10_000

        /** Read timeout when downloading the PDF (covers slow LAN conditions). */
        private const val READ_TIMEOUT_MS = 30_000
    }

    /**
     * Downloads the PDF at [url], renders every page at [PRINTER_DPI] DPI,
     * and saves each page as a PNG in getExternalFilesDir("").
     *
     * @param url   Full URL to the PDF (HTTP or HTTPS on the local network).
     * @param jobId Unique identifier used to name the output files.
     * @return      List of [RenderedPage] — one entry per PDF page, in order.
     * @throws      RuntimeException on download failure, render failure, or
     *              if external files dir is unavailable.
     */
    /**
     * Target label dimensions in dots at 203 DPI.
     * Pass these from ConfigManager so the render always fits the physical label.
     */
    var targetWidthDots:  Int = (102f * PRINTER_DPI / 25.4f).toInt()   // 102 mm → 812 dots
    var targetHeightDots: Int = (152f * PRINTER_DPI / 25.4f).toInt()   // 152 mm → 1216 dots

    fun downloadAndRender(url: String, jobId: String): List<RenderedPage> {
        Log.d(TAG, "Starting download: $url (job=$jobId)")

        val pdfFile = downloadPdf(url, jobId)
        return try {
            renderAllPages(pdfFile, jobId)
        } finally {
            // Always delete the raw PDF — we only need the rendered PNGs
            pdfFile.delete()
            Log.d(TAG, "Deleted temp PDF (job=$jobId)")
        }
    }

    // ── Private: download ─────────────────────────────────────────────────────

    private fun downloadPdf(url: String, jobId: String): File {
        val dest = File(context.cacheDir, "awb_${jobId}.pdf")

        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        conn.connectTimeout  = CONNECT_TIMEOUT_MS
        conn.readTimeout     = READ_TIMEOUT_MS
        conn.requestMethod   = "GET"
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("Accept", "application/pdf,application/octet-stream,*/*")
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android; PrinterCompanion/1.0)")

        try {
            conn.connect()
            val statusCode = conn.responseCode
            if (statusCode != HttpURLConnection.HTTP_OK) {
                throw RuntimeException("PDF download failed: HTTP $statusCode from $url")
            }

            val contentType = conn.contentType ?: ""
            if (contentType.contains("text/html")) {
                conn.disconnect()
                throw HtmlContentException(url)
            }
            if (!contentType.contains("pdf") && !contentType.contains("octet-stream")) {
                Log.w(TAG, "Unexpected Content-Type: $contentType — proceeding anyway")
            }

            conn.inputStream.use { input ->
                FileOutputStream(dest).use { output ->
                    input.copyTo(output)
                }
            }
        } finally {
            conn.disconnect()
        }

        Log.d(TAG, "Downloaded PDF: ${dest.length()} bytes (job=$jobId)")
        return dest
    }

    // ── Private: render ───────────────────────────────────────────────────────

    private fun renderAllPages(pdfFile: File, jobId: String): List<RenderedPage> {
        val outputDir = context.getExternalFilesDir(null)
            ?: throw RuntimeException(
                "External files directory is unavailable. " +
                "Check that the device has accessible external storage."
            )

        val pfd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = PdfRenderer(pfd)
        val results = mutableListOf<RenderedPage>()

        try {
            val pageCount = renderer.pageCount
            Log.d(TAG, "PDF has $pageCount page(s) (job=$jobId)")

            for (pageIndex in 0 until pageCount) {
                val rendered = renderPage(renderer, pageIndex, outputDir, jobId)
                results.add(rendered)
                Log.d(TAG, "Rendered page $pageIndex: ${rendered.filename} " +
                    "(${rendered.widthPx}×${rendered.heightDots} px, " +
                    "width=${rendered.widthBytes} bytes)")
            }
        } finally {
            renderer.close()
            pfd.close()
        }

        return results
    }

    private fun renderPage(
        renderer: PdfRenderer,
        pageIndex: Int,
        outputDir: File,
        jobId: String
    ): RenderedPage {
        val page = renderer.openPage(pageIndex)
        try {
            // Scale to fit within the target label dimensions, preserving aspect ratio.
            // This prevents overflow if the PDF is slightly larger than the physical label.
            val pdfScaleW = targetWidthDots.toFloat()  / page.width.toFloat()
            val pdfScaleH = targetHeightDots.toFloat() / page.height.toFloat()
            val scale     = minOf(pdfScaleW, pdfScaleH, PDF_TO_DOT_SCALE)

            val widthPx  = (page.width  * scale).toInt().coerceAtLeast(1)
            val heightPx = (page.height * scale).toInt().coerceAtLeast(1)

            Log.d(TAG, "Page ${pageIndex}: PDF=${page.width}×${page.height}pt " +
                "target=${targetWidthDots}×${targetHeightDots}dots " +
                "scale=%.3f → bitmap=${widthPx}×${heightPx}px".format(scale))

            // Allocate bitmap with solid white background.
            // Thermal printers need a white field; un-initialised pixels can be
            // interpreted as black by the SDK's monochrome conversion.
            val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            val canvas = Canvas(bitmap)

            // Render the PDF page into the bitmap at the computed scale
            val matrix = Matrix().apply { setScale(scale, scale) }
            page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)

            // Save as PNG to the SDK-accessible directory.
            // File name is the bare filename — the SDK resolves the directory itself.
            val filename = "awb_${jobId}_p${pageIndex}.png"
            val outFile  = File(outputDir, filename)
            FileOutputStream(outFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            bitmap.recycle()

            // SDK width param = bytes per row in 1-bit monochrome:  ceil(widthPx / 8)
            val widthBytes = (widthPx + 7) / 8

            return RenderedPage(
                filename   = filename,
                file       = outFile,
                widthPx    = widthPx,
                widthBytes = widthBytes,
                heightDots = heightPx
            )
        } finally {
            page.close()
        }
    }

    // ── Exception types ───────────────────────────────────────────────────────

    /** Thrown when the URL returns HTML instead of a PDF — triggers WebView fallback. */
    class HtmlContentException(url: String) :
        RuntimeException("URL returned HTML content (not a PDF): $url")

    // ── Result type ───────────────────────────────────────────────────────────

    /**
     * A single rendered PDF page saved to disk.
     *
     * @param filename   Bare filename in getExternalFilesDir — passed as-is to GTSPL_printBMP.
     * @param file       Full File reference used for cleanup after printing.
     * @param widthPx    Pixel width of the rendered bitmap.
     * @param widthBytes Byte width = ceil(widthPx / 8).  This is what GTSPL_printBMP expects.
     * @param heightDots Height in dots (= pixels at 1:1 for 203 DPI output).
     */
    data class RenderedPage(
        val filename: String,
        val file: File,
        val widthPx: Int,
        val widthBytes: Int,
        val heightDots: Int
    )
}
