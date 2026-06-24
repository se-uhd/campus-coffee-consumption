package de.seuhd.campuscoffee.api.security

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

    /** The decryptor that recovers the login credentials from the client's compact JWE. */
    @Bean
    fun loginPayloadDecryptor(): LoginPayloadDecryptor = LoginPayloadDecryptor(privateKey)

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
