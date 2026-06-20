package de.seuhd.campuscoffee.security
import com.nimbusds.jose.jwk.source.ImmutableSecret
import de.seuhd.campuscoffee.configuration.JwtProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import javax.crypto.spec.SecretKeySpec

/**
 * JWT crypto beans built from the symmetric HMAC secret (see [JwtProperties]). The same key both signs
 * (encoder) and verifies (decoder) tokens, so signing and verification stay in sync.
 *
 * Provided in the starter so the JWT exercise (Exercise 4) is about the token endpoint, the claims, and
 * the filter-chain wiring, not the crypto setup.
 */
@Configuration
class JwtConfig(
    private val jwtProperties: JwtProperties
) {
    /** The HMAC-SHA256 signing key derived from the configured secret. */
    private fun secretKey(): SecretKeySpec = SecretKeySpec(jwtProperties.secret.toByteArray(), MAC_ALGORITHM)

    /** Signs a token with the symmetric secret key. */
    @Bean
    fun jwtEncoder(): JwtEncoder = NimbusJwtEncoder(ImmutableSecret(secretKey()))

    /** Verifies a token against the same symmetric secret key. */
    @Bean
    fun jwtDecoder(): JwtDecoder =
        NimbusJwtDecoder
            .withSecretKey(secretKey())
            .build()

    private companion object {
        private const val MAC_ALGORITHM = "HmacSHA256"
    }
}
