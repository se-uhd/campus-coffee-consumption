package de.seuhd.campuscoffee.domain.ports.system

import de.seuhd.campuscoffee.domain.model.TotpEnrollment
import de.seuhd.campuscoffee.domain.model.TotpVerification

/**
 * Generates and verifies TOTP (RFC 6238) second factors for admins. A port in the hexagonal architecture,
 * so the TOTP library and the at-rest encryption never leak into the domain or the web layer: the raw
 * shared secret lives only inside the adapter, and callers hold and store only the encrypted form.
 */
interface TotpService {
    /**
     * Begins an enrollment: generates a fresh secret, encrypts it for storage, and builds the `otpauth://`
     * URI (and the base32 form) for the authenticator app.
     *
     * @param accountName the account label shown in the authenticator (the admin's login name)
     * @return the encrypted secret to persist plus the one-time enrollment material
     */
    fun enroll(accountName: String): TotpEnrollment

    /**
     * Encrypts a raw base32 secret for at-rest storage. Used by [enroll] and by the deterministic test
     * fixtures that seed a known secret; production login/enrollment code never supplies its own secret here.
     *
     * @param base32Secret the raw base32-encoded TOTP secret
     * @return the at-rest ciphertext
     */
    fun encrypt(base32Secret: String): String

    /**
     * Verifies a candidate [code] against an [encryptedSecret], allowing a plus/minus one time-step drift.
     *
     * @param encryptedSecret the stored at-rest ciphertext of the user's secret
     * @param code the 6-digit code the user entered
     * @return the matched verification (carrying the time step) when the code is valid, or null when it is not
     */
    fun verify(
        encryptedSecret: String,
        code: String
    ): TotpVerification?

    /**
     * Rebuilds the `otpauth://` URI for a stored (pending) secret, so the enrollment QR can be re-rendered
     * after a page reload without re-enrolling. Decrypts the secret transiently inside the adapter.
     *
     * @param encryptedSecret the stored at-rest ciphertext of the pending secret
     * @param accountName the account label shown in the authenticator (the admin's login name)
     * @return the `otpauth://totp/...` URI to encode into the QR code
     */
    fun otpauthUri(
        encryptedSecret: String,
        accountName: String
    ): String
}
