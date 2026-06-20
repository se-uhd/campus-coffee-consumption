package de.seuhd.campuscoffee.data.implementations

import de.seuhd.campuscoffee.domain.ports.CapabilityTokenGenerator
import org.springframework.stereotype.Component
import java.security.SecureRandom
import java.util.Base64

/**
 * Capability token adapter producing a high-entropy, unguessable token, per the W3C capability URL good
 * practices: 256 bits from a [SecureRandom], base64url-encoded without padding so it is URL-safe. The
 * tokens are unguessable rather than sequential, and an admin rotation simply issues a new one.
 */
@Component
class SecureRandomCapabilityTokenGenerator : CapabilityTokenGenerator {
    private val secureRandom = SecureRandom()

    override fun newToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private companion object {
        // 32 bytes = 256 bits of entropy
        private const val TOKEN_BYTES = 32
    }
}
