package co.sirdab.printer

import co.sirdab.printer.models.PrintResult
import org.junit.Test
import org.junit.Assert.*

class PrintResultTest {

    // ── Success tests ─────────────────────────────────────────────────────────

    @Test
    fun success_storesPageCount() {
        val result = PrintResult.Success(5)
        assertEquals(5, result.pagesRendered)
    }

    @Test
    fun success_isInstance() {
        val result = PrintResult.Success(3)
        assertTrue(result is PrintResult.Success)
        assertFalse(result is PrintResult.Failure)
    }

    @Test
    fun success_whenExhaustive() {
        val result: PrintResult = PrintResult.Success(2)
        val message = when (result) {
            is PrintResult.Success -> "Got ${result.pagesRendered} pages"
            is PrintResult.Failure -> "Error: ${result.code}"
        }
        assertEquals("Got 2 pages", message)
    }

    @Test
    fun success_equality() {
        val result1 = PrintResult.Success(1)
        val result2 = PrintResult.Success(1)
        assertEquals(result1, result2)
    }

    @Test
    fun success_inequality() {
        val result1 = PrintResult.Success(1)
        val result2 = PrintResult.Success(2)
        assertNotEquals(result1, result2)
    }

    // ── Failure tests ─────────────────────────────────────────────────────────

    @Test
    fun failure_storesCode() {
        val result = PrintResult.Failure("printer_unreachable", "Cannot reach 192.168.1.1")
        assertEquals("printer_unreachable", result.code)
    }

    @Test
    fun failure_storesMessage() {
        val result = PrintResult.Failure("paper_jam", "Paper jam detected")
        assertEquals("Paper jam detected", result.message)
    }

    @Test
    fun failure_defaultHttpStatus_is500() {
        val result = PrintResult.Failure("internal_error", "Something went wrong")
        assertEquals(500, result.httpStatus)
    }

    @Test
    fun failure_customHttpStatus() {
        val result = PrintResult.Failure("not_found", "Printer not found", 404)
        assertEquals(404, result.httpStatus)
    }

    @Test
    fun failure_statusCode503() {
        val result = PrintResult.Failure("unreachable", "Connection failed", 503)
        assertEquals(503, result.httpStatus)
    }

    @Test
    fun failure_statusCode422() {
        val result = PrintResult.Failure("invalid", "Invalid request", 422)
        assertEquals(422, result.httpStatus)
    }

    @Test
    fun failure_isInstance() {
        val result = PrintResult.Failure("error", "message")
        assertTrue(result is PrintResult.Failure)
        assertFalse(result is PrintResult.Success)
    }

    @Test
    fun failure_whenExhaustive() {
        val result: PrintResult = PrintResult.Failure("timeout", "Job timed out", 504)
        val message = when (result) {
            is PrintResult.Success -> "Printed ${result.pagesRendered} pages"
            is PrintResult.Failure -> "Error ${result.code}: ${result.message} (HTTP ${result.httpStatus})"
        }
        assertEquals("Error timeout: Job timed out (HTTP 504)", message)
    }

    @Test
    fun failure_equality() {
        val result1 = PrintResult.Failure("error", "msg", 500)
        val result2 = PrintResult.Failure("error", "msg", 500)
        assertEquals(result1, result2)
    }

    @Test
    fun failure_inequality_differentCode() {
        val result1 = PrintResult.Failure("error1", "msg", 500)
        val result2 = PrintResult.Failure("error2", "msg", 500)
        assertNotEquals(result1, result2)
    }

    @Test
    fun failure_inequality_differentMessage() {
        val result1 = PrintResult.Failure("error", "msg1", 500)
        val result2 = PrintResult.Failure("error", "msg2", 500)
        assertNotEquals(result1, result2)
    }

    @Test
    fun failure_inequality_differentStatus() {
        val result1 = PrintResult.Failure("error", "msg", 500)
        val result2 = PrintResult.Failure("error", "msg", 503)
        assertNotEquals(result1, result2)
    }

    // ── Type checks across sealed class ───────────────────────────────────────

    @Test
    fun typeCheck_successVsFailure() {
        val results: List<PrintResult> = listOf(
            PrintResult.Success(1),
            PrintResult.Failure("test", "msg")
        )

        val successCount = results.filterIsInstance<PrintResult.Success>().count()
        val failureCount = results.filterIsInstance<PrintResult.Failure>().count()

        assertEquals(1, successCount)
        assertEquals(1, failureCount)
    }

    @Test
    fun exhaustiveWhenMustCompile() {
        // This is a compile-time check: if a new subtype is added to PrintResult,
        // this code will not compile until both branches are handled.
        fun handleResult(result: PrintResult): String {
            return when (result) {
                is PrintResult.Success -> "Success"
                is PrintResult.Failure -> "Failure"
            }
        }

        assertEquals("Success", handleResult(PrintResult.Success(1)))
        assertEquals("Failure", handleResult(PrintResult.Failure("test", "msg")))
    }
}
