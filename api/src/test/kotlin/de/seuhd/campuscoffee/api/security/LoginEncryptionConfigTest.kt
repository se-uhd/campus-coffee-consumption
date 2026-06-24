package de.seuhd.campuscoffee.api.security

import de.seuhd.campuscoffee.api.configuration.LoginEncryptionProperties
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.util.Base64

/**
 * Unit tests for [LoginEncryptionConfig]'s key loading: a single-line PEM whose newlines are encoded as
 * literal `\n` (the Cloud Run env delivery) parses to the same key as a real-newline PEM, a key smaller than
 * 2048 bits is rejected at startup, and the published JWK reflects the configured key and the advertised
 * algorithm.
 */
class LoginEncryptionConfigTest {
    @Test
    fun `a PEM with literal backslash-n separators parses the same key as real newlines`() {
        val pem = pemOf(rsaKey(KEY_BITS))
        val singleLine = pem.replace("\n", "\\n")

        val fromNewlines = LoginEncryptionConfig(LoginEncryptionProperties(pem)).loginPublicKey()
        val fromEscaped = LoginEncryptionConfig(LoginEncryptionProperties(singleLine)).loginPublicKey()

        assertThat(fromEscaped.n).isEqualTo(fromNewlines.n)
        assertThat(fromEscaped.e).isEqualTo(fromNewlines.e)
    }

    @Test
    fun `a key smaller than 2048 bits is rejected`() {
        assertThatThrownBy { LoginEncryptionConfig(LoginEncryptionProperties(pemOf(rsaKey(WEAK_KEY_BITS)))) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("2048")
    }

    @Test
    fun `the published JWK advertises RSA-OAEP-256 encryption and the configured key id`() {
        val jwk = LoginEncryptionConfig(LoginEncryptionProperties(pemOf(rsaKey(KEY_BITS)), "my-kid")).loginPublicKey()

        assertThat(jwk.kty).isEqualTo("RSA")
        assertThat(jwk.alg).isEqualTo("RSA-OAEP-256")
        assertThat(jwk.use).isEqualTo("enc")
        assertThat(jwk.kid).isEqualTo("my-kid")
        assertThat(jwk.n).isNotBlank()
        assertThat(jwk.e).isNotBlank()
    }

    private fun rsaKey(bits: Int): PrivateKey =
        KeyPairGenerator
            .getInstance("RSA")
            .apply { initialize(bits) }
            .generateKeyPair()
            .private

    private fun pemOf(privateKey: PrivateKey): String {
        val body =
            Base64
                .getEncoder()
                .encodeToString(privateKey.encoded)
                .chunked(PEM_LINE)
                .joinToString("\n")
        return "-----BEGIN PRIVATE KEY-----\n$body\n-----END PRIVATE KEY-----"
    }

    private companion object {
        private const val KEY_BITS = 2048
        private const val WEAK_KEY_BITS = 1024
        private const val PEM_LINE = 64
    }
}
