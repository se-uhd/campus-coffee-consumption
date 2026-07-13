package de.seuhd.campuscoffee.api.security

/**
 * The two-factor enrollment state of an admin at login time, decided by [TotpLoginVerifier]. It selects the
 * scope of the minted token: an [ENROLLED] admin gets a full `ADMIN` token, a [PENDING] admin gets an
 * enrollment-only token that can reach only the enrollment endpoints until they activate a second factor.
 */
enum class EnrollmentState {
    /** The admin has an active second factor and supplied a valid code; issue a full-scope token. */
    ENROLLED,

    /** The admin has not enrolled a second factor yet; issue an enrollment-only token. */
    PENDING
}
