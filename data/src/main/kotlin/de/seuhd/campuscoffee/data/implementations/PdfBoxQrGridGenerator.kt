package de.seuhd.campuscoffee.data.implementations

import de.seuhd.campuscoffee.domain.ports.LabeledQrCode
import de.seuhd.campuscoffee.domain.ports.QrGridPdfGenerator
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.springframework.stereotype.Component
import java.io.ByteArrayOutputStream
import kotlin.math.ceil
import kotlin.math.floor

/**
 * PDF adapter backed by Apache PDFBox: lays the pre-rendered member QR codes out as a printable A4 grid, one
 * code per cell with the member's login name centered beneath it, flowing across [COLUMNS] columns and onto
 * further pages as needed. The QR PNGs carry a transparent background, so each draws cleanly over the white
 * page. The adapter holds no QR-encoding or business logic: it receives already-rendered images and only
 * arranges them, which is why it is the data-layer implementation of the [QrGridPdfGenerator] domain port.
 */
@Component
class PdfBoxQrGridGenerator : QrGridPdfGenerator {
    private val labelFont = PDType1Font(Standard14Fonts.FontName.HELVETICA)

    // The page geometry is fixed (A4), so derive the cell size and the per-page capacity once. Declaration
    // order matters: each value below builds on the previous one.
    private val cellWidth =
        (PDRectangle.A4.width - 2 * MARGIN - (COLUMNS - 1) * GUTTER) / COLUMNS
    private val qrSize = cellWidth
    private val cellHeight = qrSize + LABEL_GAP + LABEL_AREA
    private val rows =
        maxOf(1, floor((PDRectangle.A4.height - 2 * MARGIN + ROW_GAP) / (cellHeight + ROW_GAP)).toInt())
    private val perPage = COLUMNS * rows

    override fun gridPdf(entries: List<LabeledQrCode>): ByteArray {
        val out = ByteArrayOutputStream()
        PDDocument().use { document ->
            if (entries.isEmpty()) {
                drawEmptyPage(document)
            } else {
                val pageCount = ceil(entries.size / perPage.toDouble()).toInt()
                for (pageIndex in 0 until pageCount) {
                    drawPage(document, entries, pageIndex)
                }
            }
            document.save(out)
        }
        return out.toByteArray()
    }

    /** Draws the cells whose entries fall on page [pageIndex] (a slice of [entries] of at most [perPage]). */
    private fun drawPage(
        document: PDDocument,
        entries: List<LabeledQrCode>,
        pageIndex: Int
    ) {
        val page = PDPage(PDRectangle.A4)
        document.addPage(page)
        PDPageContentStream(document, page).use { stream ->
            val start = pageIndex * perPage
            val end = minOf(start + perPage, entries.size)
            for (i in start until end) {
                val onPage = i - start
                val image = PDImageXObject.createFromByteArray(document, entries[i].pngBytes, "qr")
                drawCell(stream, image, entries[i].label, onPage % COLUMNS, onPage / COLUMNS)
            }
        }
    }

    /** Draws one QR image at grid position ([col], [row]) with its [label] centered on the line below it. */
    private fun drawCell(
        stream: PDPageContentStream,
        image: PDImageXObject,
        label: String,
        col: Int,
        row: Int
    ) {
        val cellLeft = MARGIN + col * (cellWidth + GUTTER)
        val cellTop = PDRectangle.A4.height - MARGIN - row * (cellHeight + ROW_GAP)
        stream.drawImage(image, cellLeft, cellTop - qrSize, qrSize, qrSize)

        val text = fit(label, cellWidth)
        val textX = cellLeft + (cellWidth - textWidth(text, LABEL_FONT_SIZE)) / 2
        val textY = cellTop - qrSize - LABEL_GAP - LABEL_FONT_SIZE
        stream.beginText()
        stream.setFont(labelFont, LABEL_FONT_SIZE)
        stream.newLineAtOffset(textX, textY)
        stream.showText(text)
        stream.endText()
    }

    /** Draws a single page carrying a centered note, used when there are no members to show. */
    private fun drawEmptyPage(document: PDDocument) {
        val page = PDPage(PDRectangle.A4)
        document.addPage(page)
        PDPageContentStream(document, page).use { stream ->
            val width = textWidth(EMPTY_NOTE, EMPTY_FONT_SIZE)
            stream.beginText()
            stream.setFont(labelFont, EMPTY_FONT_SIZE)
            stream.newLineAtOffset((PDRectangle.A4.width - width) / 2, PDRectangle.A4.height / 2)
            stream.showText(EMPTY_NOTE)
            stream.endText()
        }
    }

    /**
     * Returns [label] sanitized for the standard font, truncated with an ellipsis if it is wider than
     * [maxWidth] at the label font size, so a long login name never overflows its cell.
     */
    private fun fit(
        label: String,
        maxWidth: Float
    ): String {
        var text = pdfSafe(label)
        if (textWidth(text, LABEL_FONT_SIZE) <= maxWidth) {
            return text
        }
        while (text.isNotEmpty() && textWidth(text + ELLIPSIS, LABEL_FONT_SIZE) > maxWidth) {
            text = text.dropLast(1)
        }
        return text + ELLIPSIS
    }

    /** The rendered width of [text] at [fontSize], in points. */
    private fun textWidth(
        text: String,
        fontSize: Float
    ): Float = labelFont.getStringWidth(text) / TEXT_UNITS_PER_EM * fontSize

    /**
     * Replaces every character the standard font cannot encode with `?`, so [PDPageContentStream.showText]
     * (which throws on an unencodable glyph) is always safe. Login names are ASCII in practice, so this is a
     * defensive fallback rather than a routine transformation.
     */
    private fun pdfSafe(label: String): String =
        label.map { if (it.code in MIN_PRINTABLE..MAX_PRINTABLE) it else '?' }.joinToString("")

    private companion object {
        // A4 grid geometry, in PDF points (1/72 inch). Three columns of square QR codes with a centered
        // login-name label beneath each.
        private const val COLUMNS = 3
        private const val MARGIN = 36f
        private const val GUTTER = 18f
        private const val ROW_GAP = 12f
        private const val LABEL_GAP = 4f
        private const val LABEL_AREA = 14f
        private const val LABEL_FONT_SIZE = 9f

        private const val EMPTY_FONT_SIZE = 14f
        private const val EMPTY_NOTE = "No active members."

        // PDFBox reports glyph advances in 1/1000 of the font size (text space units).
        private const val TEXT_UNITS_PER_EM = 1000f
        private const val ELLIPSIS = "..."

        // Printable ASCII range; the standard Helvetica font encodes these directly.
        private const val MIN_PRINTABLE = 0x20
        private const val MAX_PRINTABLE = 0x7E
    }
}
