package de.seuhd.campuscoffee.api.configuration

import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class PublicBaseUrlGuardTest {
    private fun validate(baseUrl: String) = PublicBaseUrlGuard(AppProperties(baseUrl = baseUrl)).validateBaseUrl()

    @Test
    fun `a public https base URL is accepted`() {
        assertThatCode { validate("https://coffee.se.uni-heidelberg.de") }.doesNotThrowAnyException()
    }

    @Test
    fun `a public https base URL with a port is accepted`() {
        assertThatCode { validate("https://app.example.com:8443") }.doesNotThrowAnyException()
    }

    @Test
    fun `a blank base URL is rejected`() {
        assertThatThrownBy { validate("") }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `a non-https base URL is rejected`() {
        assertThatThrownBy { validate("http://coffee.example.com") }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `a localhost base URL is rejected`() {
        assertThatThrownBy { validate("https://localhost") }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `a loopback IP base URL is rejected`() {
        assertThatThrownBy { validate("https://127.0.0.1") }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `a bare-hostname base URL is rejected`() {
        assertThatThrownBy { validate("https://app") }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
