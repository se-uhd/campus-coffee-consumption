package de.seuhd.campuscoffee.domain.ports

/**
 * Renders a member's capability URL as a QR code. A port in the hexagonal architecture: the API layer
 * asks for the image, the data/application layer supplies the adapter (backed by a QR library), so the
 * encoding library never leaks into the web layer. QR codes are generated in the backend so a member can
 * print their wall code and an admin can re-download any member's. A high-resolution PNG is the single
 * output format: it prints and embeds everywhere (Docs/Slides/Word, label printers).
 */
fun interface QrCodeGenerator {
    /**
     * Encodes [content] as a high-resolution PNG QR code.
     *
     * @param content the text to encode (the member's capability URL)
     * @param size    the side length of the square image, in pixels
     * @return the PNG image bytes
     */
    fun pngQrCode(
        content: String,
        size: Int
    ): ByteArray
}
