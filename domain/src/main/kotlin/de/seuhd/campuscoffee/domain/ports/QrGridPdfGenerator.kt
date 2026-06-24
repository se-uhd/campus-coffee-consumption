package de.seuhd.campuscoffee.domain.ports

/**
 * Renders a set of members' capability QR codes as a single printable PDF, laid out as a grid with each
 * member's label beneath their code. A port in the hexagonal architecture: the API layer supplies the
 * already-rendered QR images and asks for the document, the data layer supplies the adapter (backed by a PDF
 * library), so the PDF library never leaks into the web layer, the same boundary [QrCodeGenerator] draws for
 * the QR encoder. The intended use is an admin printing one wall sheet of everyone's coffee code at once.
 */
fun interface QrGridPdfGenerator {
    /**
     * Lays the given [entries] out as a grid of QR codes, each with its label beneath it, into one
     * print-ready PDF. The entries are placed in order, flowing across the columns and onto further pages as
     * needed. An empty list yields a valid, effectively blank PDF rather than throwing.
     *
     * @param entries the labelled QR images to place, in the order they should appear
     * @return the PDF document bytes
     */
    fun gridPdf(entries: List<LabeledQrCode>): ByteArray
}

/**
 * One cell of the QR grid: a pre-rendered QR image shown above its label (the member's login name). The QR
 * is rendered once by [QrCodeGenerator] and handed in, so this port stays purely about layout and never
 * about QR encoding.
 *
 * @property label    the text shown under the QR code (the member's login name)
 * @property pngBytes the pre-rendered QR code as PNG image bytes
 */
class LabeledQrCode(
    val label: String,
    val pngBytes: ByteArray
)
