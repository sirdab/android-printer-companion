package co.sirdab.printer

import org.junit.Test
import org.junit.Assert.*

class PrinterStatusTest {

    // ── errorMessage tests ────────────────────────────────────────────────────

    @Test
    fun errorMessage_normalStatus_returnsNull() {
        assertNull(PrinterStatus.errorMessage("00"))
    }

    @Test
    fun errorMessage_printingStatus_returnsNull() {
        assertNull(PrinterStatus.errorMessage("20"))
    }

    @Test
    fun errorMessage_headOpen_returnsErrorMessage() {
        val error = PrinterStatus.errorMessage("01")
        assertEquals("Printer head is open — close the cover and retry", error)
    }

    @Test
    fun errorMessage_paperJam_returnsErrorMessage() {
        val error = PrinterStatus.errorMessage("02")
        assertEquals("Paper jam — clear the jam and retry", error)
    }

    @Test
    fun errorMessage_paperJamAndHeadOpen_returnsErrorMessage() {
        val error = PrinterStatus.errorMessage("03")
        assertEquals("Paper jam and head open — clear jam, close cover and retry", error)
    }

    @Test
    fun errorMessage_outOfPaper_returnsErrorMessage() {
        val error = PrinterStatus.errorMessage("04")
        assertEquals("Out of paper — reload label roll", error)
    }

    @Test
    fun errorMessage_outOfPaperAndHeadOpen_returnsErrorMessage() {
        val error = PrinterStatus.errorMessage("05")
        assertEquals("Out of paper and head open — reload labels and close cover", error)
    }

    @Test
    fun errorMessage_outOfRibbon_returnsErrorMessage() {
        val error = PrinterStatus.errorMessage("08")
        assertEquals("Out of ribbon", error)
    }

    @Test
    fun errorMessage_outOfRibbonAndHeadOpen_returnsErrorMessage() {
        val error = PrinterStatus.errorMessage("09")
        assertEquals("Out of ribbon and head open", error)
    }

    @Test
    fun errorMessage_outOfRibbonAndPaperJam_returnsErrorMessage() {
        val error = PrinterStatus.errorMessage("0A")
        assertEquals("Out of ribbon and paper jam", error)
    }

    @Test
    fun errorMessage_outOfRibbonAndPaperJamAndHeadOpen_returnsErrorMessage() {
        val error = PrinterStatus.errorMessage("0B")
        assertEquals("Out of ribbon, paper jam and head open", error)
    }

    @Test
    fun errorMessage_outOfRibbonAndOutOfPaper_returnsErrorMessage() {
        val error = PrinterStatus.errorMessage("0C")
        assertEquals("Out of ribbon and out of paper", error)
    }

    @Test
    fun errorMessage_outOfRibbonAndOutOfPaperAndHeadOpen_returnsErrorMessage() {
        val error = PrinterStatus.errorMessage("0D")
        assertEquals("Out of ribbon, out of paper and head open", error)
    }

    @Test
    fun errorMessage_paused_returnsErrorMessage() {
        val error = PrinterStatus.errorMessage("10")
        assertEquals("Printer is paused — press the feed button to resume", error)
    }

    @Test
    fun errorMessage_hardwareError_returnsErrorMessage() {
        val error = PrinterStatus.errorMessage("80")
        assertEquals("Printer hardware error — power-cycle the printer", error)
    }

    @Test
    fun errorMessage_unknownStatus_returnsNull() {
        assertNull(PrinterStatus.errorMessage("99"))
    }

    @Test
    fun errorMessage_caseInsensitive_lowercase() {
        val error = PrinterStatus.errorMessage("0a")
        assertEquals("Out of ribbon and paper jam", error)
    }

    @Test
    fun errorMessage_caseInsensitive_mixedCase() {
        val error = PrinterStatus.errorMessage("0A")
        assertEquals("Out of ribbon and paper jam", error)
    }

    // ── state tests ───────────────────────────────────────────────────────────

    @Test
    fun state_normalStatus_returnsReady() {
        assertEquals("ready", PrinterStatus.state("00"))
    }

    @Test
    fun state_printingStatus_returnsPrinting() {
        assertEquals("printing", PrinterStatus.state("20"))
    }

    @Test
    fun state_pausedStatus_returnsPaused() {
        assertEquals("paused", PrinterStatus.state("10"))
    }

    @Test
    fun state_outOfPaper_returnsOutOfPaper() {
        assertEquals("out_of_paper", PrinterStatus.state("04"))
        assertEquals("out_of_paper", PrinterStatus.state("05"))
    }

    @Test
    fun state_outOfRibbon_returnsOutOfRibbon() {
        assertEquals("out_of_ribbon", PrinterStatus.state("08"))
        assertEquals("out_of_ribbon", PrinterStatus.state("09"))
        assertEquals("out_of_ribbon", PrinterStatus.state("0C"))
        assertEquals("out_of_ribbon", PrinterStatus.state("0D"))
    }

    @Test
    fun state_paperJam_returnsPaperJam() {
        assertEquals("paper_jam", PrinterStatus.state("02"))
        assertEquals("paper_jam", PrinterStatus.state("03"))
        assertEquals("paper_jam", PrinterStatus.state("0A"))
        assertEquals("paper_jam", PrinterStatus.state("0B"))
    }

    @Test
    fun state_headOpen_returnsHeadOpen() {
        assertEquals("head_open", PrinterStatus.state("01"))
    }

    @Test
    fun state_hardwareError_returnsError() {
        assertEquals("error", PrinterStatus.state("80"))
    }

    @Test
    fun state_unknownStatus_returnsUnknown() {
        assertEquals("unknown", PrinterStatus.state("99"))
    }

    @Test
    fun state_caseInsensitive() {
        assertEquals("out_of_ribbon", PrinterStatus.state("0c"))
        assertEquals("out_of_ribbon", PrinterStatus.state("0C"))
    }

    // ── description tests ─────────────────────────────────────────────────────

    @Test
    fun description_normalStatus_returnsReady() {
        assertEquals("Ready", PrinterStatus.description("00"))
    }

    @Test
    fun description_printingStatus_returnsPrinting() {
        assertEquals("Printing", PrinterStatus.description("20"))
    }

    @Test
    fun description_errorCode_returnsErrorMessage() {
        assertEquals("Printer head is open — close the cover and retry",
            PrinterStatus.description("01"))
        assertEquals("Paper jam — clear the jam and retry",
            PrinterStatus.description("02"))
        assertEquals("Out of paper — reload label roll",
            PrinterStatus.description("04"))
    }

    @Test
    fun description_unknownStatus_returnsGenericDescription() {
        assertEquals("Unknown status (99)", PrinterStatus.description("99"))
    }

    @Test
    fun description_caseInsensitive() {
        assertEquals("Ready", PrinterStatus.description("00"))
        assertEquals("Ready", PrinterStatus.description("00"))
    }
}
