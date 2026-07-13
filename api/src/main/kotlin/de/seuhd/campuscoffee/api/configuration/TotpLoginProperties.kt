package de.seuhd.campuscoffee.api.configuration

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Configuration for the login-time hardening of the admin TOTP second factor, bound from
 * `campus-coffee.auth.totp.*`. On top of the per-IP [LoginRateLimitProperties] guard, two per-account
 * defences bound an attacker who already holds an admin's password: a per-account failure lockout and a
 * per-user code-reuse (time-step) guard. Both are on by default and are turned off only in the shared test
 * profile, where many admin logins happen in one 30-second step with the same code.
 *
 * @property lockoutEnabled whether the per-account TOTP failure lockout is active (default true; off in tests).
 * @property maxFailures the number of failed code attempts a single admin may make within [window] before
 *   being refused with 429.
 * @property window the sliding window over which [maxFailures] is counted (and after which a locked account
 *   may try again).
 * @property stepReuseGuardEnabled whether the per-user code-reuse guard is active (default true; off in
 *   tests). When on, a code whose 30-second time step was already accepted for that admin is rejected, so a
 *   captured still-valid code cannot be replayed inside its window.
 */
@ConfigurationProperties("campus-coffee.auth.totp")
data class TotpLoginProperties(
    val lockoutEnabled: Boolean = true,
    val maxFailures: Int = DEFAULT_MAX_FAILURES,
    val window: Duration = Duration.ofMinutes(DEFAULT_WINDOW_MINUTES),
    val stepReuseGuardEnabled: Boolean = true
) {
    init {
        require(maxFailures >= 1) { "campus-coffee.auth.totp.max-failures must be at least 1." }
        require(!window.isZero && !window.isNegative) {
            "campus-coffee.auth.totp.window must be a positive duration."
        }
    }

    private companion object {
        private const val DEFAULT_MAX_FAILURES = 5
        private const val DEFAULT_WINDOW_MINUTES = 15L
    }
}
