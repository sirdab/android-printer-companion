package co.sirdab.printer.models

/**
 * Sealed result returned by PrinterClient.print().
 * Maps directly to the HTTP response the webapp receives.
 */
sealed class PrintResult {

    /**
     * All pages rendered and sent to the printer successfully.
     * @param pagesRendered Number of PDF pages that were printed.
     */
    data class Success(val pagesRendered: Int) : PrintResult()

    /**
     * Something went wrong. The companion app returns this as a non-200 JSON response.
     *
     * @param code        Machine-readable error key (e.g. "printer_unreachable")
     * @param message     Human-readable description suitable for logging / displaying to a picker
     * @param httpStatus  HTTP status code to return to the caller (400, 422, 500, 503…)
     */
    data class Failure(
        val code: String,
        val message: String,
        val httpStatus: Int = 500
    ) : PrintResult()
}
