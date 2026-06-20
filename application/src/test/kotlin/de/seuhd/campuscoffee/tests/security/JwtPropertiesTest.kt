package de.seuhd.campuscoffee.tests.security

import de.seuhd.campuscoffee.configuration.JwtProperties
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Unit tests for [JwtProperties], which guards the HMAC signing secret: a secret long enough for
 * HMAC-SHA256 binds, a too-short one fails fast.
 */
class JwtPropertiesTest {
    @Test
    fun `a secret of at least 32 bytes is accepted`() {
        val secret = "a".repeat(32)

        assertThat(JwtProperties(secret).secret).isEqualTo(secret)
    }

    @Test
    fun `a secret shorter than 32 bytes is rejected`() {
        assertThatThrownBy { JwtProperties("too-short") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("at least")
    }
}
