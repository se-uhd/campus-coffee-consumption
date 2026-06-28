package de.seuhd.campuscoffee.api.configuration

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Configuration for the brute-force / denial-of-service protection on the admin login endpoint
 * (`POST /api/auth/token`). A client (keyed on its IP) that accumulates [maxFailures] failed attempts within
 * a sliding [window] is refused with 429 until the window refills, bounding both online password guessing
 * and the bcrypt CPU cost an attacker can force. A successful login resets the client's counter.
 *
 * The limiter is in-memory and per instance, so on a multi-instance deployment the effective limit is the
 * configured value times the instance count; that is acceptable for this small internal tool, and is why the
 * window and threshold are set conservatively.
 *
 * The client key is derived per [clientIpStrategy]; the default ignores `X-Forwarded-For` (safe off-prod),
 * while a proxied deployment such as Cloud Run reads the trusted hop from the right (see [trustedProxyCount]).
 *
 * @property enabled whether the limiter is active (default true; turned off only where a test drives many
 *   deliberate failures from one client).
 * @property maxFailures the number of failed attempts a single client may make within [window] before being
 *   refused with 429.
 * @property window the sliding window over which [maxFailures] is counted (and after which a blocked client
 *   is allowed to try again).
 * @property clientIpStrategy how the client IP that the limiter keys on is derived (default
 *   [ClientIpStrategy.REMOTE_ADDR], which ignores the spoofable `X-Forwarded-For`; set
 *   [ClientIpStrategy.FORWARDED_FOR] behind a trusted proxy).
 * @property trustedProxyCount the number of trusted reverse proxies in front of the app, each appending one
 *   `X-Forwarded-For` entry; under [ClientIpStrategy.FORWARDED_FOR] the client IP is read as this many entries
 *   from the right, so an attacker cannot shift the key by prepending entries. Default 1 (direct Cloud Run,
 *   one Google Front End hop); set 2 if an L7 load balancer is placed in front. Ignored by
 *   [ClientIpStrategy.REMOTE_ADDR].
 */
@ConfigurationProperties("campus-coffee.auth.rate-limit")
data class LoginRateLimitProperties(
    val enabled: Boolean = true,
    val maxFailures: Int = DEFAULT_MAX_FAILURES,
    val window: Duration = Duration.ofMinutes(DEFAULT_WINDOW_MINUTES),
    val clientIpStrategy: ClientIpStrategy = ClientIpStrategy.REMOTE_ADDR,
    val trustedProxyCount: Int = DEFAULT_TRUSTED_PROXY_COUNT
) {
    init {
        require(maxFailures >= 1) { "campus-coffee.auth.rate-limit.max-failures must be at least 1." }
        require(!window.isZero && !window.isNegative) {
            "campus-coffee.auth.rate-limit.window must be a positive duration."
        }
        require(trustedProxyCount >= 0) {
            "campus-coffee.auth.rate-limit.trusted-proxy-count must be zero or positive."
        }
    }

    private companion object {
        private const val DEFAULT_MAX_FAILURES = 10
        private const val DEFAULT_WINDOW_MINUTES = 15L
        private const val DEFAULT_TRUSTED_PROXY_COUNT = 1
    }
}
