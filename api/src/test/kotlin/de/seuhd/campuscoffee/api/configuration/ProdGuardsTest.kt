package de.seuhd.campuscoffee.api.configuration

import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Unit tests for the prod startup guards: [PublicBaseUrlGuard] rejects an empty, non-https, loopback, or
 * bare-hostname base URL, and [WeakDevSecretGuard] rejects the committed dev-only fallback JWT secret and RSA
 * login key so a prod deployment cannot run with public credentials.
 */
class ProdGuardsTest {
    private val realSecret = "x".repeat(32)
    private val realKeyPem = "-----BEGIN PRIVATE KEY-----\nMIIBrealcontent\n-----END PRIVATE KEY-----"

    @Test
    fun `validateBaseUrl accepts a public https origin`() {
        assertThatCode { PublicBaseUrlGuard(AppProperties("https://coffee.example.run.app")).validateBaseUrl() }
            .doesNotThrowAnyException()
    }

    @Test
    fun `validateBaseUrl rejects an empty base URL`() {
        assertThatThrownBy { PublicBaseUrlGuard(AppProperties("")).validateBaseUrl() }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("must be set")
    }

    @Test
    fun `validateBaseUrl rejects a plain-http base URL`() {
        assertThatThrownBy { PublicBaseUrlGuard(AppProperties("http://coffee.example.com")).validateBaseUrl() }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("https")
    }

    @Test
    fun `validateBaseUrl rejects an https localhost base URL`() {
        assertThatThrownBy { PublicBaseUrlGuard(AppProperties("https://localhost:8080")).validateBaseUrl() }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("public host")
    }

    @Test
    fun `validateBaseUrl rejects an https loopback IP base URL`() {
        assertThatThrownBy { PublicBaseUrlGuard(AppProperties("https://127.0.0.1")).validateBaseUrl() }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("public host")
    }

    @Test
    fun `validateBaseUrl rejects an https bare hostname base URL`() {
        assertThatThrownBy { PublicBaseUrlGuard(AppProperties("https://app")).validateBaseUrl() }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("public host")
    }

    @Test
    fun `rejectCommittedDevSecrets accepts real secrets`() {
        val guard = WeakDevSecretGuard(JwtProperties(realSecret), LoginEncryptionProperties(realKeyPem))

        assertThatCode { guard.rejectCommittedDevSecrets() }.doesNotThrowAnyException()
    }

    @Test
    fun `rejectCommittedDevSecrets rejects the committed dev JWT secret`() {
        val devSecret = "dev-only-insecure-jwt-secret-change-me-in-production"
        val guard = WeakDevSecretGuard(JwtProperties(devSecret), LoginEncryptionProperties(realKeyPem))

        assertThatThrownBy { guard.rejectCommittedDevSecrets() }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("JWT_SECRET")
    }

    @Test
    fun `rejectCommittedDevSecrets rejects the committed dev login key`() {
        val devKeyPem =
            "-----BEGIN PRIVATE KEY-----\n" +
                "MIIEvAIBADANBgkqhkiG9w0BAQEFAASCBKYwggSiAgEAAoIBAQCMOOezSrpFsR9K\n" +
                "-----END PRIVATE KEY-----"
        val guard = WeakDevSecretGuard(JwtProperties(realSecret), LoginEncryptionProperties(devKeyPem))

        assertThatThrownBy { guard.rejectCommittedDevSecrets() }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("LOGIN_PRIVATE_KEY_PEM")
    }
}
