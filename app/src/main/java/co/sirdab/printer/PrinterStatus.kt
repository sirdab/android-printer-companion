package co.sirdab.printer

/**
 * Handles conversion of printer status codes to human-readable state and error messages.
 *
 * Printer status codes (from GTSPL_printersStatus):
 *   00 Normal · 01 Head open · 02 Paper jam · 04 Out of paper
 *   08 Out of ribbon · 10 Paused · 20 Printing · 80 Other error
 */
internal object PrinterStatus {

    /**
     * Returns a non-null error message if the status code indicates a problem
     * that should fail the print job. Returns null for "normal" and "printing".
     *
     * Note: status 0x20 (printing) is not treated as an error here because
     * it can appear during the brief window before the job is fully spooled.
     */
    fun errorMessage(code: String): String? = when (code.uppercase()) {
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

    fun state(code: String): String = when (code.uppercase()) {
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

    fun description(code: String): String =
        errorMessage(code) ?: when (code.uppercase()) {
            "00" -> "Ready"
            "20" -> "Printing"
            else -> "Unknown status ($code)"
        }
}
