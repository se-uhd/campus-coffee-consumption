package de.seuhd.campuscoffee.domain.ports.system

/**
 * One cell of the QR grid: a pre-rendered QR image shown above its label (the user's login name). The QR is
 * rendered once by [QrCodeService.pngQrCode] and handed in, so the grid layout stays purely about placement
 * and never about QR encoding.
 *
 * @property label the text shown under the QR code (the user's login name)
 * @property pngBytes the pre-rendered QR code as PNG image bytes
 */
class LabeledQrCode(
    val label: String,
    val pngBytes: ByteArray
)
