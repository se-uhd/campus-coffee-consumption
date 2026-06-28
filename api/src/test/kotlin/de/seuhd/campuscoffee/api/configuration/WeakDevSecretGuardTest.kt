package de.seuhd.campuscoffee.api.configuration

import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class WeakDevSecretGuardTest {
    // a >= 32-byte secret that is not the committed dev fallback
    private val freshSecret = "fresh-production-jwt-secret-at-least-32-bytes-long"

    // the exact committed dev fallback the guard must reject
    private val committedDevJwtSecret = "dev-only-insecure-jwt-secret-change-me-in-production"

    // a fragment of the committed dev RSA key body the guard matches on
    private val committedDevKeyFragment = "MIIEvAIBADANBgkqhkiG9w0BAQEFAASCBKYwggSiAgEAAoIBAQCMOOezSrpFsR9K"

    // a PEM that carries the required marker but none of the dev key body
    private val freshKeyPem =
        "-----BEGIN PRIVATE KEY-----\n" +
            "RnJlc2hOb25EZXZQa2NzOEtleUJvZHlOb3RUaGVEZXZGcmFnbWVudA==\n" +
            "-----END PRIVATE KEY-----"

    private fun guard(
        secret: String,
        keyPem: String
    ) = WeakDevSecretGuard(JwtProperties(secret = secret), LoginEncryptionProperties(privateKeyPem = keyPem))

    @Test
    fun `the committed dev JWT secret is rejected`() {
        assertThatThrownBy { guard(committedDevJwtSecret, freshKeyPem).rejectCommittedDevSecrets() }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("jwt.secret")
    }

    @Test
    fun `the committed dev login key is rejected with real newlines`() {
        val pem = "-----BEGIN PRIVATE KEY-----\n$committedDevKeyFragment\nmoreBody\n-----END PRIVATE KEY-----"
        assertThatThrownBy { guard(freshSecret, pem).rejectCommittedDevSecrets() }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("private-key-pem")
    }

    @Test
    fun `the committed dev login key is rejected with literal newline escapes`() {
        // a single-line PEM whose newlines are the literal two-character escape \n, as Secret Manager may store it
        val pem = "-----BEGIN PRIVATE KEY-----\\n$committedDevKeyFragment\\nmoreBody\\n-----END PRIVATE KEY-----"
        assertThatThrownBy { guard(freshSecret, pem).rejectCommittedDevSecrets() }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("private-key-pem")
    }

    @Test
    fun `a fresh secret and a fresh key are accepted`() {
        assertThatCode { guard(freshSecret, freshKeyPem).rejectCommittedDevSecrets() }.doesNotThrowAnyException()
    }
}
