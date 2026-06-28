package de.seuhd.campuscoffee.api.security

import de.seuhd.campuscoffee.api.configuration.ClientIpStrategy
import de.seuhd.campuscoffee.api.configuration.LoginRateLimitProperties
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatExceptionOfType
import org.junit.jupiter.api.Test

/**
 * Unit tests for [ClientIpResolver]: under `FORWARDED_FOR` it reads the trusted hop from the right of
 * `X-Forwarded-For` (so a rotated prefix cannot move the key), and otherwise it falls back to the
 * unspoofable socket peer.
 */
class ClientIpResolverTest {
    private fun resolver(
        strategy: ClientIpStrategy,
        trustedProxyCount: Int = 1
    ) = ClientIpResolver(LoginRateLimitProperties(clientIpStrategy = strategy, trustedProxyCount = trustedProxyCount))

    @Test
    fun `a rotated X-Forwarded-For prefix resolves to the same trusted hop`() {
        val resolver = resolver(ClientIpStrategy.FORWARDED_FOR)
        assertThat(resolver.clientIp("1.1.1.1, 203.0.113.9", "10.0.0.1")).isEqualTo("203.0.113.9")
        assertThat(resolver.clientIp("9.9.9.9, 203.0.113.9", "10.0.0.1")).isEqualTo("203.0.113.9")
    }

    @Test
    fun `FORWARDED_FOR with one trusted proxy takes the rightmost hop`() {
        val resolver = resolver(ClientIpStrategy.FORWARDED_FOR)
        assertThat(resolver.clientIp("a, b, 198.51.100.5", "10.0.0.1")).isEqualTo("198.51.100.5")
    }

    @Test
    fun `FORWARDED_FOR honors a single-hop header`() {
        val resolver = resolver(ClientIpStrategy.FORWARDED_FOR)
        assertThat(resolver.clientIp("198.51.100.23", "10.0.0.1")).isEqualTo("198.51.100.23")
    }

    @Test
    fun `FORWARDED_FOR with two trusted proxies takes the second hop from the right`() {
        val resolver = resolver(ClientIpStrategy.FORWARDED_FOR, trustedProxyCount = 2)
        assertThat(resolver.clientIp("junk, 203.0.113.7, 10.0.0.1", "10.0.0.9")).isEqualTo("203.0.113.7")
    }

    @Test
    fun `FORWARDED_FOR falls back to the socket peer when the header is absent`() {
        val resolver = resolver(ClientIpStrategy.FORWARDED_FOR)
        assertThat(resolver.clientIp(null, "192.0.2.1")).isEqualTo("192.0.2.1")
    }

    @Test
    fun `FORWARDED_FOR falls back to the socket peer when the chain is shorter than the trusted-proxy count`() {
        val resolver = resolver(ClientIpStrategy.FORWARDED_FOR, trustedProxyCount = 2)
        assertThat(resolver.clientIp("198.51.100.7", "192.0.2.2")).isEqualTo("192.0.2.2")
    }

    @Test
    fun `REMOTE_ADDR ignores X-Forwarded-For and uses the socket peer`() {
        val resolver = resolver(ClientIpStrategy.REMOTE_ADDR)
        assertThat(resolver.clientIp("5.5.5.5", "192.0.2.3")).isEqualTo("192.0.2.3")
    }

    @Test
    fun `FORWARDED_FOR ignores blank and whitespace hops`() {
        val resolver = resolver(ClientIpStrategy.FORWARDED_FOR)
        assertThat(resolver.clientIp("7.7.7.7, , 203.0.113.4 , ", "10.0.0.1")).isEqualTo("203.0.113.4")
    }

    @Test
    fun `a blank header and a null socket peer resolve to unknown`() {
        val resolver = resolver(ClientIpStrategy.FORWARDED_FOR)
        assertThat(resolver.clientIp("", null)).isEqualTo("unknown")
    }

    @Test
    fun `a negative trusted-proxy count is rejected`() {
        assertThatExceptionOfType(IllegalArgumentException::class.java)
            .isThrownBy { LoginRateLimitProperties(trustedProxyCount = -1) }
            .withMessageContaining("trusted-proxy-count")
    }
}
