package de.seuhd.campuscoffee.api.support

import de.seuhd.campuscoffee.api.security.EnrollmentState
import de.seuhd.campuscoffee.api.security.TotpAttemptLimiter
import de.seuhd.campuscoffee.api.security.TotpReplayGuard
import de.seuhd.campuscoffee.domain.model.persistedId
import de.seuhd.campuscoffee.domain.ports.api.UserService
import de.seuhd.campuscoffee.domain.ports.system.TotpService
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.stereotype.Component

/**
 * The second-factor step of the admin login, run after the password has been verified. It decides whether
 * the account is enrolled and, for an enrolled admin, requires a valid current code.
 *
 * A wrong or missing code (for an enrolled admin) throws a [BadCredentialsException] so it flows through the
 * token endpoint's existing catch arm to the identical `401 "Invalid credentials."`, keeping wrong password
 * and wrong code indistinguishable (no oracle). A not-yet-enrolled admin passes with no code and is issued an
 * enrollment-only token by the caller. Brute-forcing the 6-digit code is bounded by the per-account
 * [TotpAttemptLimiter] (a 429 once locked), and reuse of a still-valid code within its window is blocked by
 * the monotonic [TotpReplayGuard]. This runs only after a correct password, so a caller who does not know the
 * password never reaches it.
 */
@Component
class TotpLoginVerifier(
    private val userService: UserService,
    private val totpService: TotpService,
    private val totpAttemptLimiter: TotpAttemptLimiter,
    private val totpReplayGuard: TotpReplayGuard
) {
    /**
     * Verifies the second factor for the just-authenticated [loginName].
     *
     * @param loginName the admin whose password was already verified
     * @param code the authenticator code from the login payload, or null when none was supplied
     * @return [EnrollmentState.ENROLLED] when a valid code was supplied for an enrolled admin, or
     *   [EnrollmentState.PENDING] when the admin has not enrolled a second factor yet
     * @throws BadCredentialsException if an enrolled admin supplied a missing, wrong, or replayed code
     * @throws de.seuhd.campuscoffee.api.exceptions.TooManyLoginAttemptsException if the account is locked out
     */
    fun verifyForLogin(
        loginName: String,
        code: String?
    ): EnrollmentState {
        val user = userService.getByLoginName(loginName)
        if (user.totpEnabled != true) {
            // not enrolled: ignore any code; the caller issues an enrollment-only token
            return EnrollmentState.PENDING
        }
        val userKey = user.persistedId.toString()
        // refuse a locked-out account before doing any crypto (429); only reachable by a password holder
        totpAttemptLimiter.ensureWithinLimit(userKey)
        val secret = user.totpSecret
        val verification = if (code != null && secret != null) totpService.verify(secret, code) else null
        if (verification == null || !totpReplayGuard.accept(userKey, verification.matchedTimeStep)) {
            totpAttemptLimiter.recordFailure(userKey)
            throw BadCredentialsException("Invalid credentials.")
        }
        totpAttemptLimiter.recordSuccess(userKey)
        return EnrollmentState.ENROLLED
    }
}
