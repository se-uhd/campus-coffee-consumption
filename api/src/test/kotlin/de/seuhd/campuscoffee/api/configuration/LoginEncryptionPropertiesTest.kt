package de.seuhd.campuscoffee.api.configuration

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Unit tests for [LoginEncryptionProperties], which guards the login-encryption private key: a PEM-shaped
 * value binds, a value with no PEM marker fails fast, and the key id has a default.
 */
class LoginEncryptionPropertiesTest {
    @Test
    fun `a PEM-encoded private key is accepted`() {
        val pem = "-----BEGIN PRIVATE KEY-----\nMIIBcontent\n-----END PRIVATE KEY-----"

        assertThat(LoginEncryptionProperties(pem).privateKeyPem).isEqualTo(pem)
    }

    @Test
    fun `a value without a PEM marker is rejected`() {
        assertThatThrownBy { LoginEncryptionProperties("not-a-pem") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("PEM")
    }

    @Test
    fun `the key id defaults when not set`() {
        val pem = "-----BEGIN PRIVATE KEY-----\nMIIBcontent\n-----END PRIVATE KEY-----"

        assertThat(LoginEncryptionProperties(pem).keyId).isEqualTo("login-key-1")
    }
}
