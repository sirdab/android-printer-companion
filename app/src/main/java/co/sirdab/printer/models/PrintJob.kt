package co.sirdab.printer.models

/**
 * Represents a single print request received from the webapp.
 *
 * @param pdfUrl      Full URL to the PDF (downloaded by the companion app)
 * @param printerIp   Optional override; falls back to value stored in ConfigManager
 * @param printerPort Optional override; falls back to ConfigManager (default 8899)
 * @param copies      Number of copies per page (1–10)
 */
data class PrintJob(
    val pdfUrl: String,
    val printerIp: String?,
    val printerPort: Int?,
    val copies: Int = 1
)
