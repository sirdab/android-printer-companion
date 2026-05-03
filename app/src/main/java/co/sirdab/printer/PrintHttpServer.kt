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
 *   GET  /health      — basic liveness check
 *   OPTIONS *         — CORS preflight
 *
 * This server is owned by [KeepAliveService] and therefore survives
 * independently of the Activity lifecycle.  [PrinterClient] is accessed via
 * [MainActivity.instance] when a print or status request arrives — if the
 * Activity is not yet ready, print/status requests return a 503.
 */
class PrintHttpServer(
    private val service: KeepAliveService
) : NanoHTTPD(PORT) {

    companion object {
        const val PORT = 8080
        private const val TAG = "PrintHttpServer"
        private const val MIME_JSON = "application/json"
    }

    // Disable gzip — NanoHTTPD's gzip encoding causes "Broken pipe" /
    // "Connection reset" errors when the client closes the connection before
    // the compressed stream is flushed.
    override fun useGzipWhenAccepted(r: Response): Boolean = false

    private val configManager  get() = service.configManager
    private val mainActivity   get() = MainActivity.instance?.get()
    private val printerClient  get() = mainActivity?.printerClient

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

    private fun handlePrint(session: IHTTPSession): Response {
        val client = printerClient
            ?: return errorResponse(503, "printer_service_unavailable",
                "Printer service is initializing — please wait a moment and try again.")

        val body = parseJsonBody(session)
            ?: return errorResponse(400, "invalid_body",
                "Request body must be valid JSON with Content-Type: application/json")

        val pdfUrl = body.optString("pdfUrl").takeIf { it.isNotBlank() }
            ?: return errorResponse(400, "missing_pdfUrl",
                "Required field 'pdfUrl' is missing or empty")

        val printerIp   = body.optString("printerIp").takeIf { it.isNotBlank() }
        val printerPort = if (body.has("printerPort")) body.optInt("printerPort") else null
        val copies      = body.optInt("copies", 1).coerceIn(1, 10)

        val job = PrintJob(
            pdfUrl      = pdfUrl,
            printerIp   = printerIp,
            printerPort = printerPort,
            copies      = copies
        )

        mainActivity?.updateStatus("Printing…")

        return when (val result = client.print(job)) {
            is PrintResult.Success -> {
                mainActivity?.updateStatus("Ready — last print OK (${result.pagesRendered}p)")
                mainActivity?.recordLastPrint(success = true, detail = "${result.pagesRendered} page(s)")
                jsonResponse(200, buildMap {
                    put("ok",    true)
                    put("pages", result.pagesRendered)
                })
            }

            is PrintResult.Failure -> {
                mainActivity?.updateStatus("Error: ${result.message}")
                mainActivity?.recordLastPrint(success = false, detail = result.message)
                jsonResponse(result.httpStatus, buildMap {
                    put("ok",     false)
                    put("error",  result.code)
                    put("detail", result.message)
                })
            }
        }
    }

    private fun handleStatus(): Response {
        val client = printerClient
        if (client == null) {
            return jsonResponse(200, buildMap {
                put("ok",         true)
                put("configured", configManager.isConfigured())
                put("printer",    mapOf(
                    "state"       to "initializing",
                    "description" to "Printer service starting",
                    "raw"         to ""
                ))
            })
        }
        return jsonResponse(200, buildMap {
            put("ok",         true)
            put("configured", configManager.isConfigured())
            put("printer",    client.getStatus())
        })
    }

    private fun handleGetConfig(): Response {
        return jsonResponse(200, buildMap {
            put("ok", true)
            putAll(configManager.toMap())
        })
    }

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

        mainActivity?.refreshConfigDisplay()
        Log.i(TAG, "Config updated: ${configManager.toMap()}")

        return jsonResponse(200, buildMap {
            put("ok", true)
            putAll(configManager.toMap())
        })
    }

    private fun handleHealth(): Response {
        return jsonResponse(200, mapOf(
            "ok"      to true,
            "service" to "printer-companion",
            "version" to "1.0.0"
        ))
    }

    // ── CORS ──────────────────────────────────────────────────────────────────

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
