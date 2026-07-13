package de.seuhd.campuscoffee.api.security

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import de.seuhd.campuscoffee.api.configuration.TotpLoginProperties
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * Rejects reuse of a TOTP code within its validity window. A code stays valid for one time step plus the
 * plus/minus one drift band (roughly 90 seconds), and the login-payload replay guard only single-uses the
 * exact ciphertext, so a fresh JWE wrapping the same still-valid code would otherwise pass. This guard is
 * **monotonic per admin**: it remembers the highest time step accepted for each user id and accepts only a
 * strictly greater one, so neither the just-used step nor an earlier still-in-window step can be replayed.
 *
 * State is per instance, in a size-bounded Caffeine cache that evicts idle accounts; the last step only needs
 * to outlive the drift window, so entries expire well before they could matter again. Because the high-water
 * mark is in memory, the single-use guarantee holds within one instance; a horizontally scaled deployment
 * that wanted cross-instance code-reuse resistance would back it with a shared store. This matches the
 * per-instance scope of the login rate limiter, and is acceptable at this app's single-group scale.
 *
 * @property properties the second-factor hardening settings, including the guard's on/off switch.
 */
@Component
class TotpReplayGuard(
    private val properties: TotpLoginProperties
) {
    private val lastAcceptedStep: Cache<String, Long> =
        Caffeine
            .newBuilder()
            .expireAfterWrite(RETENTION)
            .maximumSize(MAX_TRACKED_ACCOUNTS)
            .build()

    /**
     * Records [timeStep] as accepted for [userKey] and reports whether it was strictly newer than the last
     * step accepted for that admin. A non-newer step (a reuse of the just-used or an earlier in-window code)
     * returns false and leaves the stored high-water mark unchanged.
     *
     * @param userKey the admin's stable user id as a string.
     * @param timeStep the matched 30-second time step of the code being verified.
     * @return true when the step is newer than any previously accepted for the account (accept), false on reuse.
     */
    fun accept(
        userKey: String,
        timeStep: Long
    ): Boolean {
        if (!properties.stepReuseGuardEnabled) {
            return true
        }
        var accepted = false
        // Caffeine's asMap().compute is atomic per key, so two concurrent logins cannot both accept the
        // same step
        lastAcceptedStep.asMap().compute(userKey) { _, previous ->
            if (previous == null || timeStep > previous) {
                accepted = true
                timeStep
            } else {
                accepted = false
                previous
            }
        }
        return accepted
    }

    private companion object {
        private val RETENTION: Duration = Duration.ofMinutes(10)
        private const val MAX_TRACKED_ACCOUNTS = 100_000L
    }
}
