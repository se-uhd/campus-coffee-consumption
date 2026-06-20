package de.seuhd.campuscoffee.data.implementations

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.client.j2se.MatrixToImageWriter
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import de.seuhd.campuscoffee.domain.ports.QrCodeGenerator
import org.springframework.stereotype.Component
import java.io.ByteArrayOutputStream

/**
 * QR-code adapter backed by ZXing: encodes the member's capability URL into a square QR matrix and writes
 * it as a high-resolution PNG via ImageIO. A quiet-zone margin and a medium error-correction level keep
 * the printed wall code scannable.
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
        return ByteArrayOutputStream().use { stream ->
            MatrixToImageWriter.writeToStream(matrix, "PNG", stream)
            stream.toByteArray()
        }
    }

    private companion object {
        // quiet-zone width in modules around the code (the scannable margin)
        private const val QUIET_ZONE_MODULES = 2
    }
}
