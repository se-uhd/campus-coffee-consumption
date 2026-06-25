package de.seuhd.campuscoffee.api.exceptions

import java.time.Duration

/**
 * Raised when a client exceeds the login attempt rate limit on `POST /api/auth/token`. Mapped to HTTP 429
 * Too Many Requests with a `Retry-After` header by the global exception handler.
 *
 * @property retryAfter how long the client should wait before the next attempt is allowed.
 */
class TooManyLoginAttemptsException(
    val retryAfter: Duration
) : RuntimeException("Too many login attempts; try again later.")
