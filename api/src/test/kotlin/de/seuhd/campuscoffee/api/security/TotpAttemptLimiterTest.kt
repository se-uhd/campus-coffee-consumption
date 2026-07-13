package de.seuhd.campuscoffee.api.security

import de.seuhd.campuscoffee.api.configuration.TotpLoginProperties
import de.seuhd.campuscoffee.api.exceptions.TooManyLoginAttemptsException
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Unit tests for the per-account TOTP failure lockout: an account is refused once it uses up its failure
 * budget, a success resets it, and a disabled limiter never refuses.
 */
class TotpAttemptLimiterTest {
    private val user = "user-1"

    private fun limiter(
        enabled: Boolean = true,
        maxFailures: Int = 3
    ): TotpAttemptLimiter =
        TotpAttemptLimiter(
            TotpLoginProperties(
                lockoutEnabled = enabled,
                maxFailures = maxFailures,
                window = Duration.ofMinutes(15)
            )
        )

    @Test
    fun `refuses the account once the failure budget is used up`() {
        val limiter = limiter(maxFailures = 3)
        repeat(3) {
            assertThatCode { limiter.ensureWithinLimit(user) }.doesNotThrowAnyException()
            limiter.recordFailure(user)
        }
        assertThatThrownBy { limiter.ensureWithinLimit(user) }
            .isInstanceOf(TooManyLoginAttemptsException::class.java)
    }

    @Test
    fun `a success resets the account's budget`() {
        val limiter = limiter(maxFailures = 3)
        repeat(3) { limiter.recordFailure(user) }
        limiter.recordSuccess(user)
        assertThatCode { limiter.ensureWithinLimit(user) }.doesNotThrowAnyException()
    }

    @Test
    fun `keys the budget per account`() {
        val limiter = limiter(maxFailures = 1)
        limiter.recordFailure("a")
        assertThatCode { limiter.ensureWithinLimit("b") }.doesNotThrowAnyException()
    }

    @Test
    fun `never refuses when the lockout is disabled`() {
        val limiter = limiter(enabled = false, maxFailures = 1)
        repeat(10) { limiter.recordFailure(user) }
        assertThatCode { limiter.ensureWithinLimit(user) }.doesNotThrowAnyException()
    }
}
