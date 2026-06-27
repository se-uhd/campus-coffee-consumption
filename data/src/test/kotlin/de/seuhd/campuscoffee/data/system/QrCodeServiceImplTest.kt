package de.seuhd.campuscoffee.data.system

import de.seuhd.campuscoffee.domain.model.LabeledQrCode
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Unit tests for [QrCodeServiceImpl], the data-layer adapter that lays user QR codes out as a printable
 * PDF grid. The output must be a valid PDF, carry every user's login-name label, and paginate as the
 * user count grows, all without a database or the web layer. Real QR PNGs come from [QrCodeServiceImpl]
 * so the image-embedding path is exercised end to end.
 */
class QrCodeServiceImplTest {
    private val qrCode = QrCodeServiceImpl()

    private fun entry(label: String): LabeledQrCode =
        LabeledQrCode(label, qrCode.pngQrCode("https://example.org/login/$label", QR_PX))

    @Test
    fun `gridPdf renders a valid PDF carrying every label on one page`() {
        val labels = listOf("jane_doe", "maxmustermann", "erika", "john_roe")

        val bytes = qrCode.gridPdf(labels.map { entry(it) })

        assertThat(bytes.copyOfRange(0, PDF_MAGIC.length).decodeToString()).isEqualTo(PDF_MAGIC)
        Loader.loadPDF(bytes).use { document ->
            assertThat(document.numberOfPages).isEqualTo(1)
            val text = PDFTextStripper().getText(document)
            labels.forEach { assertThat(text).contains(it) }
        }
    }

    @Test
    fun `gridPdf paginates once the entries exceed a single page`() {
        val entries = (0 until 13).map { entry("user$it") }

        Loader.loadPDF(qrCode.gridPdf(entries)).use { document ->
            assertThat(document.numberOfPages).isEqualTo(2)
        }
    }

    @Test
    fun `gridPdf on an empty list yields a valid one-page PDF`() {
        val bytes = qrCode.gridPdf(emptyList())

        assertThat(bytes.copyOfRange(0, PDF_MAGIC.length).decodeToString()).isEqualTo(PDF_MAGIC)
        Loader.loadPDF(bytes).use { document ->
            assertThat(document.numberOfPages).isEqualTo(1)
        }
    }

    @Test
    fun `gridPdf renders an over-long label without failing`() {
        val bytes = qrCode.gridPdf(listOf(entry("x".repeat(200))))

        assertThat(bytes.copyOfRange(0, PDF_MAGIC.length).decodeToString()).isEqualTo(PDF_MAGIC)
    }

    private companion object {
        private const val QR_PX = 128
        private const val PDF_MAGIC = "%PDF"
    }
}
