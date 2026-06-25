package de.seuhd.campuscoffee.api.security

import de.seuhd.campuscoffee.api.configuration.LoginRateLimitProperties
import de.seuhd.campuscoffee.api.exceptions.TooManyLoginAttemptsException
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatExceptionOfType
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Unit tests for [LoginAttemptLimiter]: a client stays allowed under its failure budget, is refused with
 * [TooManyLoginAttemptsException] once the budget is spent, is cleared by a success, is never blocked when
 * the limiter is off, and does not share its budget with another client.
 */
class LoginAttemptLimiterTest {
    private fun limiter(
        enabled: Boolean = true,
        maxFailures: Int = 3
    ) = LoginAttemptLimiter(
        LoginRateLimitProperties(enabled = enabled, maxFailures = maxFailures, window = Duration.ofMinutes(15))
    )

    @Test
    fun `ensureWithinLimit allows a client still under the failure budget`() {
        val limiter = limiter(maxFailures = 3)
        repeat(2) { limiter.recordFailure(IP) }

        assertThatCode { limiter.ensureWithinLimit(IP) }.doesNotThrowAnyException()
    }

    @Test
    fun `ensureWithinLimit throws TooManyLoginAttemptsException once the failure budget is spent`() {
        val limiter = limiter(maxFailures = 3)
        repeat(3) { limiter.recordFailure(IP) }

        assertThatExceptionOfType(TooManyLoginAttemptsException::class.java)
            .isThrownBy { limiter.ensureWithinLimit(IP) }
    }

    @Test
    fun `recordSuccess clears a client's accumulated failures`() {
        val limiter = limiter(maxFailures = 3)
        repeat(3) { limiter.recordFailure(IP) }

        limiter.recordSuccess(IP)

        assertThatCode { limiter.ensureWithinLimit(IP) }.doesNotThrowAnyException()
    }

    @Test
    fun `a disabled limiter never blocks a client`() {
        val limiter = limiter(enabled = false, maxFailures = 1)
        repeat(10) { limiter.recordFailure(IP) }

        assertThatCode { limiter.ensureWithinLimit(IP) }.doesNotThrowAnyException()
    }

    @Test
    fun `one client's spent budget does not block a different client`() {
        val limiter = limiter(maxFailures = 2)
        repeat(2) { limiter.recordFailure(IP) }

        assertThatExceptionOfType(TooManyLoginAttemptsException::class.java)
            .isThrownBy { limiter.ensureWithinLimit(IP) }
        assertThatCode { limiter.ensureWithinLimit("203.0.113.99") }.doesNotThrowAnyException()
    }

    private companion object {
        private const val IP = "10.0.0.1"
    }
}
