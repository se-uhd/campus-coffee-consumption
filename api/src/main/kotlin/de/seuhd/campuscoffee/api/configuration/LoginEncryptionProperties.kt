package de.seuhd.campuscoffee.api.configuration

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Configuration for the login-payload encryption key. The frontend encrypts the credentials with the
 * matching RSA public key (published as a JWK), so only this private key can decrypt them; see
 * `LoginEncryptionConfig` and `LoginPayloadDecryptor`. The key is required (binding fails fast if it is
 * absent or not a PEM) and must be identical on every instance, so it is supplied as configuration rather
 * than generated per startup: a client may fetch the public key from one instance and post the ciphertext
 * to another. The bit-length check (at least 2048 bits) runs once the PEM is parsed, in `LoginEncryptionConfig`.
 *
 * @property privateKeyPem the RSA private key in PKCS#8 PEM form (`-----BEGIN PRIVATE KEY-----`); required.
 * @property keyId the JWK `kid` advertised with the public key, so a client (or a future rotation) can
 *   identify which key a ciphertext was encrypted under.
 */
@ConfigurationProperties("campus-coffee.login-encryption")
data class LoginEncryptionProperties(
    val privateKeyPem: String,
    val keyId: String = "login-key-1"
) {
    init {
        require(privateKeyPem.contains(PEM_MARKER)) {
            "campus-coffee.login-encryption.private-key-pem must be a PKCS#8 PEM RSA private key " +
                "(missing the '$PEM_MARKER' marker)."
        }
    }

    private companion object {
        private const val PEM_MARKER = "BEGIN PRIVATE KEY"
    }
}
