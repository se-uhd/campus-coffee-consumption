package de.seuhd.campuscoffee.api.security

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import de.seuhd.campuscoffee.api.configuration.TotpLoginProperties
import de.seuhd.campuscoffee.api.exceptions.TooManyLoginAttemptsException
import io.github.bucket4j.Bucket
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * In-memory brute-force guard for the second factor, keyed on the **admin's user id** rather than the client
 * IP. It closes the gap the per-IP [LoginAttemptLimiter] leaves: an attacker who already knows an admin's
 * password only needs to guess the 6-digit code, and could do so from many IPs. Each admin gets a Bucket4j
 * token bucket of capacity [TotpLoginProperties.maxFailures] refilling over [TotpLoginProperties.window]; a
 * token is consumed per wrong code and the bucket is reset on a successful login. The limiter is reached
 * only after a correct password, so a caller who does not know the password never triggers it (and it
 * therefore never reveals whether an account exists or is enrolled).
 *
 * State is per instance, in a size-bounded Caffeine cache that evicts idle keys, mirroring
 * [LoginAttemptLimiter].
 *
 * @property properties the configured lockout budget, window, and on/off switch.
 */
@Component
class TotpAttemptLimiter(
    private val properties: TotpLoginProperties
) {
    private val buckets: Cache<String, Bucket> =
        Caffeine
            .newBuilder()
            .expireAfterAccess(properties.window.multipliedBy(2))
            .maximumSize(MAX_TRACKED_ACCOUNTS)
            .build()

    /**
     * Refuses the current attempt with a [TooManyLoginAttemptsException] (429) when [userKey] has used up its
     * allowance. Reads the bucket without consuming a token, so a within-limit account is not charged for the
     * check.
     *
     * @param userKey the admin's stable user id as a string.
     */
    fun ensureWithinLimit(userKey: String) {
        if (!properties.lockoutEnabled) {
            return
        }
        val probe = bucketFor(userKey).estimateAbilityToConsume(1)
        if (!probe.canBeConsumed()) {
            throw TooManyLoginAttemptsException(Duration.ofNanos(probe.nanosToWaitForRefill))
        }
    }

    /**
     * Charges one failed code attempt against [userKey]'s bucket.
     *
     * @param userKey the admin's stable user id as a string.
     */
    fun recordFailure(userKey: String) {
        if (!properties.lockoutEnabled) {
            return
        }
        bucketFor(userKey).tryConsume(1)
    }

    /**
     * Resets [userKey]'s bucket after a successful login.
     *
     * @param userKey the admin's stable user id as a string.
     */
    fun recordSuccess(userKey: String) {
        if (!properties.lockoutEnabled) {
            return
        }
        buckets.invalidate(userKey)
    }

    /** Returns the account's bucket, creating a fresh full one on first use. */
    private fun bucketFor(userKey: String): Bucket = buckets.get(userKey) { newBucket() }

    /** Builds a new token bucket with the configured capacity and refill window. */
    private fun newBucket(): Bucket {
        val capacity = properties.maxFailures.toLong()
        return Bucket
            .builder()
            .addLimit { limit -> limit.capacity(capacity).refillGreedy(capacity, properties.window) }
            .build()
    }

    private companion object {
        private const val MAX_TRACKED_ACCOUNTS = 100_000L
    }
}
