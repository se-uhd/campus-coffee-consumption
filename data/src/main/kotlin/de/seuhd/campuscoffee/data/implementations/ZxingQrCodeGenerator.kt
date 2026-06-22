package de.seuhd.campuscoffee.data.implementations

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.client.j2se.MatrixToImageConfig
import com.google.zxing.client.j2se.MatrixToImageWriter
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import de.seuhd.campuscoffee.domain.ports.QrCodeGenerator
import org.springframework.stereotype.Component
import java.io.ByteArrayOutputStream

/**
 * QR-code adapter backed by ZXing: encodes the member's capability URL into a square QR matrix and writes
 * it as a high-resolution PNG via ImageIO. The dark modules are opaque black on a fully transparent
 * background (the PNG carries an alpha channel), so the code sits on any wall color or print stock without
 * a white box. A quiet-zone margin and a medium error-correction level keep the printed wall code scannable.
 */
@Component
class ZxingQrCodeGenerator : QrCodeGenerator {
    override fun pngQrCode(
        content: String,
        size: Int
    ): ByteArray {
        val hints =
            mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to QUIET_ZONE_MODULES,
                EncodeHintType.CHARACTER_SET to "UTF-8"
            )
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
        val colors = MatrixToImageConfig(ON_COLOR_ARGB, OFF_COLOR_ARGB)
        return ByteArrayOutputStream().use { stream ->
            MatrixToImageWriter.writeToStream(matrix, "PNG", stream, colors)
            stream.toByteArray()
        }
    }

    private companion object {
        // quiet-zone width in modules around the code (the scannable margin)
        private const val QUIET_ZONE_MODULES = 2

        // opaque black for the dark modules (0xAARRGGBB)
        private const val ON_COLOR_ARGB: Int = 0xFF000000.toInt()

        // fully transparent for the light modules, so the PNG has an alpha channel and no white background
        private const val OFF_COLOR_ARGB: Int = 0x00FFFFFF
    }
}
