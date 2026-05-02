package co.sirdab.printer

import co.sirdab.printer.models.PrintJob
import org.junit.Test
import org.junit.Assert.*

class PrintJobTest {

    // ── Constructor and default values tests ──────────────────────────────────

    @Test
    fun printJob_defaultCopies_isOne() {
        val job = PrintJob(
            pdfUrl = "https://example.com/label.pdf",
            printerIp = "192.168.1.100",
            printerPort = 8899
        )
        assertEquals(1, job.copies)
    }

    @Test
    fun printJob_allFieldsSet() {
        val job = PrintJob(
            pdfUrl = "https://example.com/label.pdf",
            printerIp = "192.168.1.100",
            printerPort = 8899,
            copies = 3
        )

        assertEquals("https://example.com/label.pdf", job.pdfUrl)
        assertEquals("192.168.1.100", job.printerIp)
        assertEquals(8899, job.printerPort)
        assertEquals(3, job.copies)
    }

    @Test
    fun printJob_nullPrinterIp_allowed() {
        val job = PrintJob(
            pdfUrl = "https://example.com/label.pdf",
            printerIp = null,
            printerPort = 8899,
            copies = 1
        )

        assertNull(job.printerIp)
        assertEquals(8899, job.printerPort)
    }

    @Test
    fun printJob_nullPrinterPort_allowed() {
        val job = PrintJob(
            pdfUrl = "https://example.com/label.pdf",
            printerIp = "192.168.1.100",
            printerPort = null,
            copies = 1
        )

        assertEquals("192.168.1.100", job.printerIp)
        assertNull(job.printerPort)
    }

    @Test
    fun printJob_bothNullPrinterConfig_allowed() {
        val job = PrintJob(
            pdfUrl = "https://example.com/label.pdf",
            printerIp = null,
            printerPort = null
        )

        assertNull(job.printerIp)
        assertNull(job.printerPort)
        assertEquals(1, job.copies)
    }

    // ── Data class behavior tests ─────────────────────────────────────────────

    @Test
    fun printJob_copy_modifyCopies() {
        val job1 = PrintJob(
            pdfUrl = "https://example.com/label.pdf",
            printerIp = "192.168.1.100",
            printerPort = 8899,
            copies = 1
        )

        val job2 = job1.copy(copies = 5)

        assertEquals(1, job1.copies)
        assertEquals(5, job2.copies)
        assertEquals(job1.pdfUrl, job2.pdfUrl)
        assertEquals(job1.printerIp, job2.printerIp)
        assertEquals(job1.printerPort, job2.printerPort)
    }

    @Test
    fun printJob_copy_modifyPrinterIp() {
        val job1 = PrintJob(
            pdfUrl = "https://example.com/label.pdf",
            printerIp = "192.168.1.100",
            printerPort = 8899
        )

        val job2 = job1.copy(printerIp = "10.0.0.1")

        assertEquals("192.168.1.100", job1.printerIp)
        assertEquals("10.0.0.1", job2.printerIp)
        assertEquals(job1.pdfUrl, job2.pdfUrl)
        assertEquals(job1.printerPort, job2.printerPort)
    }

    @Test
    fun printJob_copy_modifyUrl() {
        val job1 = PrintJob(
            pdfUrl = "https://example.com/label.pdf",
            printerIp = "192.168.1.100",
            printerPort = 8899
        )

        val job2 = job1.copy(pdfUrl = "https://example.com/new-label.pdf")

        assertEquals("https://example.com/label.pdf", job1.pdfUrl)
        assertEquals("https://example.com/new-label.pdf", job2.pdfUrl)
    }

    // ── Equality tests ────────────────────────────────────────────────────────

    @Test
    fun printJob_equality_identicalValues() {
        val job1 = PrintJob(
            pdfUrl = "https://example.com/label.pdf",
            printerIp = "192.168.1.100",
            printerPort = 8899,
            copies = 2
        )

        val job2 = PrintJob(
            pdfUrl = "https://example.com/label.pdf",
            printerIp = "192.168.1.100",
            printerPort = 8899,
            copies = 2
        )

        assertEquals(job1, job2)
    }

    @Test
    fun printJob_inequality_differentUrl() {
        val job1 = PrintJob(
            pdfUrl = "https://example.com/label1.pdf",
            printerIp = "192.168.1.100",
            printerPort = 8899
        )

        val job2 = PrintJob(
            pdfUrl = "https://example.com/label2.pdf",
            printerIp = "192.168.1.100",
            printerPort = 8899
        )

        assertNotEquals(job1, job2)
    }

    @Test
    fun printJob_inequality_differentIp() {
        val job1 = PrintJob(
            pdfUrl = "https://example.com/label.pdf",
            printerIp = "192.168.1.100",
            printerPort = 8899
        )

        val job2 = PrintJob(
            pdfUrl = "https://example.com/label.pdf",
            printerIp = "192.168.1.101",
            printerPort = 8899
        )

        assertNotEquals(job1, job2)
    }

    @Test
    fun printJob_inequality_differentPort() {
        val job1 = PrintJob(
            pdfUrl = "https://example.com/label.pdf",
            printerIp = "192.168.1.100",
            printerPort = 8899
        )

        val job2 = PrintJob(
            pdfUrl = "https://example.com/label.pdf",
            printerIp = "192.168.1.100",
            printerPort = 9000
        )

        assertNotEquals(job1, job2)
    }

    @Test
    fun printJob_inequality_differentCopies() {
        val job1 = PrintJob(
            pdfUrl = "https://example.com/label.pdf",
            printerIp = "192.168.1.100",
            printerPort = 8899,
            copies = 1
        )

        val job2 = PrintJob(
            pdfUrl = "https://example.com/label.pdf",
            printerIp = "192.168.1.100",
            printerPort = 8899,
            copies = 2
        )

        assertNotEquals(job1, job2)
    }

    // ── Hash code tests ───────────────────────────────────────────────────────

    @Test
    fun printJob_hashCode_consistentForEqualObjects() {
        val job1 = PrintJob(
            pdfUrl = "https://example.com/label.pdf",
            printerIp = "192.168.1.100",
            printerPort = 8899,
            copies = 2
        )

        val job2 = PrintJob(
            pdfUrl = "https://example.com/label.pdf",
            printerIp = "192.168.1.100",
            printerPort = 8899,
            copies = 2
        )

        assertEquals(job1.hashCode(), job2.hashCode())
    }

    @Test
    fun printJob_inCollection() {
        val job1 = PrintJob(
            pdfUrl = "https://example.com/label.pdf",
            printerIp = "192.168.1.100",
            printerPort = 8899
        )

        val job2 = PrintJob(
            pdfUrl = "https://example.com/label.pdf",
            printerIp = "192.168.1.100",
            printerPort = 8899
        )

        val set = setOf(job1)
        assertTrue(set.contains(job2))
    }

    @Test
    fun printJob_variousCopyCounts() {
        for (copies in 1..10) {
            val job = PrintJob(
                pdfUrl = "https://example.com/label.pdf",
                printerIp = "192.168.1.100",
                printerPort = 8899,
                copies = copies
            )
            assertEquals(copies, job.copies)
        }
    }
}
