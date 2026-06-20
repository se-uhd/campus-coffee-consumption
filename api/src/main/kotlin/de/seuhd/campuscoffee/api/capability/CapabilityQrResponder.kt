package de.seuhd.campuscoffee.api.capability

import de.seuhd.campuscoffee.domain.model.objects.User
import de.seuhd.campuscoffee.domain.ports.QrCodeGenerator
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component

/**
 * Renders a member's capability QR code as an HTTP response: a single high-resolution PNG that prints and
 * embeds everywhere. Shared by the member's own profile endpoint and the admin's per-user endpoint.
 */
@Component
class CapabilityQrResponder(
    private val capabilityUrlFactory: CapabilityUrlFactory,
    private val qrCodeGenerator: QrCodeGenerator
) {
    /**
     * Builds the PNG QR-code response for [user]'s capability URL.
     *
     * @param user the member whose capability URL to encode
     * @return the PNG image with the `image/png` content type
     */
    fun qrResponse(user: User): ResponseEntity<ByteArray> {
        val token = requireNotNull(user.capabilityToken) { "User '${user.loginName}' has no capability token." }
        val url = capabilityUrlFactory.urlFor(token)
        return ResponseEntity
            .ok()
            .contentType(MediaType.IMAGE_PNG)
            .body(qrCodeGenerator.pngQrCode(url, PNG_SIZE_PX))
    }

    private companion object {
        private const val PNG_SIZE_PX = 1024
    }
}
