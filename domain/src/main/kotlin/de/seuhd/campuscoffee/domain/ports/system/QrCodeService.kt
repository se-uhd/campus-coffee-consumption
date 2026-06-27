package de.seuhd.campuscoffee.domain.ports.system

import de.seuhd.campuscoffee.domain.model.LabeledQrCode

/**
 * Renders users' capability URLs as QR codes: a single high-resolution PNG (printed on the wall or embedded
 * in a document) and a printable PDF grid of many labeled codes (an admin's one-sheet print of everyone's
 * code). A port in the hexagonal architecture, so the QR-encoding and PDF libraries never leak into the web
 * layer: the API layer asks for the bytes and the data layer supplies the adapter.
 */
interface QrCodeService {
    /**
     * Encodes [content] as a high-resolution PNG QR code.
     *
     * @param content the text to encode (the user's capability URL)
     * @param size the side length of the square image, in pixels
     * @return the PNG image bytes
     */
    fun pngQrCode(
        content: String,
        size: Int
    ): ByteArray

    /**
     * Lays the given [entries] out as a grid of QR codes, each with its label beneath it, into one
     * print-ready PDF. The entries are placed in order, flowing across the columns and onto further pages as
     * needed. An empty list yields a valid, effectively blank PDF rather than throwing.
     *
     * @param entries the labeled QR images to place, in the order they should appear
     * @return the PDF document bytes
     */
    fun gridPdf(entries: List<LabeledQrCode>): ByteArray
}
