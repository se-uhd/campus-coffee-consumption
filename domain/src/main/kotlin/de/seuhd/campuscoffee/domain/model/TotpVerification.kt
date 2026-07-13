package de.seuhd.campuscoffee.domain.model

/**
 * A successful TOTP code verification, carrying the 30-second time step the code matched. The login path
 * uses [matchedTimeStep] to reject reuse of a code within its window, accepting only a strictly later step
 * per user.
 *
 * @property matchedTimeStep the counter (Unix time in seconds divided by the 30-second period) the accepted
 *   code was generated for
 */
data class TotpVerification(
    val matchedTimeStep: Long
)
