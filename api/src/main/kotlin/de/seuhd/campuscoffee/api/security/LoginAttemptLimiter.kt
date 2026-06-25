package de.seuhd.campuscoffee.api.security

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import de.seuhd.campuscoffee.api.configuration.LoginRateLimitProperties
import de.seuhd.campuscoffee.api.exceptions.TooManyLoginAttemptsException
import io.github.bucket4j.Bucket
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * In-memory brute-force and DoS guard for the admin login endpoint. Each client (keyed on its IP) gets a
 * Bucket4j token bucket of capacity [LoginRateLimitProperties.maxFailures], refilling over
 * [LoginRateLimitProperties.window]. A token is consumed per failed attempt; a successful login resets the
 * client's bucket. When the bucket is empty the next attempt is refused before any RSA-decrypt or bcrypt work
 * runs, so repeated wrong credentials cannot keep guessing passwords or use up CPU.
 *
 * The buckets live in a size-bounded Caffeine cache that evicts idle keys, so abandoned client keys do not
 * pile up in memory. State is per instance (see [LoginRateLimitProperties]).
 *
 * @property properties the configured threshold, window, and on/off switch.
 */
@Component
class LoginAttemptLimiter(
    private val properties: LoginRateLimitProperties
) {
    private val buckets: Cache<String, Bucket> =
        Caffeine
            .newBuilder()
            // keep an idle client's bucket a little past the window so a returning attacker does not get a
            // fresh allowance the instant the window elapses; bounded so the map cannot grow without limit
            .expireAfterAccess(properties.window.multipliedBy(2))
            .maximumSize(MAX_TRACKED_CLIENTS)
            .build()

    /**
     * Refuses the current attempt with a [TooManyLoginAttemptsException] (429) when [clientKey] has no
     * remaining allowance. It reads the bucket without consuming a token, so a within-limit client is not
     * counted for being checked; a token is taken on a failure via [recordFailure].
     *
     * @param clientKey the per-client rate-limit key (the client IP).
     */
    fun ensureWithinLimit(clientKey: String) {
        if (!properties.enabled) {
            return
        }
        val probe = bucketFor(clientKey).estimateAbilityToConsume(1)
        if (!probe.canBeConsumed()) {
            throw TooManyLoginAttemptsException(Duration.ofNanos(probe.nanosToWaitForRefill))
        }
    }

    /**
     * Charges one failed attempt against [clientKey]'s bucket.
     *
     * @param clientKey the per-client rate-limit key (the client IP).
     */
    fun recordFailure(clientKey: String) {
        if (!properties.enabled) {
            return
        }
        bucketFor(clientKey).tryConsume(1)
    }

    /**
     * Resets [clientKey]'s bucket after a successful login, so a legitimate admin who mistyped a few times is
     * not held to the failure count once they get in.
     *
     * @param clientKey the per-client rate-limit key (the client IP).
     */
    fun recordSuccess(clientKey: String) {
        if (!properties.enabled) {
            return
        }
        buckets.invalidate(clientKey)
    }

    /** Returns the client's bucket, creating a fresh full one on first use. */
    private fun bucketFor(clientKey: String): Bucket = buckets.get(clientKey) { newBucket() }

    /** Builds a new token bucket with the configured capacity and refill window. */
    private fun newBucket(): Bucket {
        val capacity = properties.maxFailures.toLong()
        return Bucket
            .builder()
            .addLimit { limit -> limit.capacity(capacity).refillGreedy(capacity, properties.window) }
            .build()
    }

    private companion object {
        // an upper bound on distinct client keys held at once, so requests from many source IPs cannot make
        // the limiter use up memory
        private const val MAX_TRACKED_CLIENTS = 100_000L
    }
}
