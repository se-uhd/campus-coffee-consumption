package de.seuhd.campuscoffee.domain.model

/**
 * The result of beginning a TOTP (two-factor) enrollment: the encrypted secret to persist plus the material
 * the admin needs to add the account to their authenticator app. The raw secret never becomes stored state;
 * it is shown once here (as [base32Secret] for manual entry and inside [otpauthUri] for the QR code) and
 * afterwards only its [encryptedSecret] form is kept.
 *
 * @property encryptedSecret the at-rest ciphertext of the TOTP secret, stored on the user
 * @property otpauthUri the `otpauth://totp/...` URI encoded into the enrollment QR code
 * @property base32Secret the raw base32 secret shown once for manual entry into an authenticator app
 */
data class TotpEnrollment(
    val encryptedSecret: String,
    val otpauthUri: String,
    val base32Secret: String
)
