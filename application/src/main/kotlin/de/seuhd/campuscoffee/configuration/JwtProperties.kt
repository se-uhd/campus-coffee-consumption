package de.seuhd.campuscoffee.configuration

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Configuration for the JWT signing key. The HMAC secret signs and verifies the stateless bearer
 * tokens; it is required and must be long enough for HMAC-SHA256 (at least 32 bytes), so binding fails
 * at startup otherwise rather than producing weak tokens later.
 *
 * @property secret the HMAC signing secret for the stateless JWT bearer tokens; required, at least 32 bytes.
 */
@ConfigurationProperties("campus-coffee.jwt")
data class JwtProperties(
    val secret: String
) {
    init {
        require(secret.toByteArray().size >= MIN_SECRET_BYTES) {
            "campus-coffee.jwt.secret must be at least $MIN_SECRET_BYTES bytes for HMAC-SHA256."
        }
    }

    companion object {
        private const val MIN_SECRET_BYTES = 32
    }
}
