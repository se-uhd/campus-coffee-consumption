package de.seuhd.campuscoffee.api.security

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.nimbusds.jose.JWEAlgorithm
import com.nimbusds.jose.jwk.KeyUse
import com.nimbusds.jose.jwk.RSAKey
import de.seuhd.campuscoffee.api.configuration.LoginEncryptionProperties
import de.seuhd.campuscoffee.api.dtos.PublicKeyDto
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.converter.RsaKeyConverters
import java.security.KeyFactory
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAPublicKeySpec
import java.time.Clock

/**
 * Login-encryption crypto beans built from the configured RSA private key (see [LoginEncryptionProperties]).
 * The private key decrypts the login payload; its public half is published as a JWK so the frontend can
 * encrypt the credentials before sending them. The key is parsed and validated once at startup, mirroring
 * how [JwtConfig] turns the configured secret into the JWT signing beans.
 *
 * @param properties the configured private key (PKCS#8 PEM) and its advertised key id.
 */
@Configuration
class LoginEncryptionConfig(
    properties: LoginEncryptionProperties
) {
    private val privateKey: RSAPrivateCrtKey = parsePrivateKey(properties.privateKeyPem)
    private val publicJwk: RSAKey = buildPublicJwk(privateKey, properties.keyId)
    private val maxPayloadAge = properties.maxPayloadAge

    /**
     * The decryptor that recovers the login credentials from the client's compact JWE, rejecting a stale or
     * replayed payload. The replay guard is a Caffeine cache of seen-ciphertext fingerprints, evicted after
     * twice the freshness window so it covers the whole window in which a captured ciphertext is still valid
     * without growing unbounded.
     */
    @Bean
    fun loginPayloadDecryptor(): LoginPayloadDecryptor {
        val seenPayloads: Cache<String, Boolean> =
            Caffeine
                .newBuilder()
                .expireAfterWrite(maxPayloadAge.multipliedBy(2))
                .maximumSize(REPLAY_CACHE_MAX_ENTRIES)
                .build()
        // putIfAbsent returns null only when the fingerprint was absent, i.e. this is its first use
        val replayGuard =
            LoginReplayGuard { fingerprint ->
                seenPayloads.asMap().putIfAbsent(fingerprint, true) == null
            }
        return LoginPayloadDecryptor(privateKey, Clock.systemUTC(), maxPayloadAge, replayGuard)
    }

    /** The public key published at `GET /api/auth/public-key`, shaped as a JWK for the browser to import. */
    @Bean
    fun loginPublicKey(): PublicKeyDto =
        PublicKeyDto(
            kty = publicJwk.keyType.value,
            n = publicJwk.modulus.toString(),
            e = publicJwk.publicExponent.toString(),
            alg = publicJwk.algorithm.name,
            use = publicJwk.keyUse.value,
            kid = publicJwk.keyID
        )

    private companion object {
        private const val MIN_KEY_BITS = 2048

        // an upper bound on remembered login-ciphertext fingerprints, so the replay guard cannot grow without
        // bound and use up memory; entries also expire after twice the freshness window
        private const val REPLAY_CACHE_MAX_ENTRIES = 100_000L

        /**
         * Parses the PKCS#8 PEM into an RSA private key with CRT parameters, using Spring Security's PEM
         * converter rather than hand-rolling the base64 decode, and enforces the minimum key size.
         *
         * @param pem the PKCS#8 PEM-encoded private key.
         */
        fun parsePrivateKey(pem: String): RSAPrivateCrtKey {
            // A single-line env var (the prod/Cloud Run delivery) carries the PEM with literal "\n"
            // separators; turn them back into the real newlines a PEM needs. The dev/test keys already use
            // real newlines, so this is a no-op for them.
            val normalizedPem = pem.replace("\\n", "\n")
            val key =
                requireNotNull(RsaKeyConverters.pkcs8().convert(normalizedPem.byteInputStream())) {
                    "campus-coffee.login-encryption.private-key-pem could not be parsed as a PKCS#8 RSA private key."
                }
            require(key is RSAPrivateCrtKey) {
                "campus-coffee.login-encryption.private-key-pem must be an RSA private key with CRT parameters."
            }
            require(key.modulus.bitLength() >= MIN_KEY_BITS) {
                "campus-coffee.login-encryption key must be at least $MIN_KEY_BITS bits."
            }
            return key
        }

        /**
         * Derives the public JWK from the private key's modulus and public exponent (so no separate public
         * key is configured), tagging it with the key id, the encryption use, and the RSA-OAEP-256 algorithm.
         *
         * @param privateKey the RSA private key to derive the public key from.
         * @param keyId the JWK `kid` to advertise.
         */
        fun buildPublicJwk(
            privateKey: RSAPrivateCrtKey,
            keyId: String
        ): RSAKey {
            val publicKey =
                KeyFactory
                    .getInstance("RSA")
                    .generatePublic(RSAPublicKeySpec(privateKey.modulus, privateKey.publicExponent)) as RSAPublicKey
            return RSAKey
                .Builder(publicKey)
                .keyID(keyId)
                .keyUse(KeyUse.ENCRYPTION)
                .algorithm(JWEAlgorithm.RSA_OAEP_256)
                .build()
        }
    }
}
