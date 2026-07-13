package de.seuhd.campuscoffee.api.dtos

/**
 * Response body for `POST /api/users/me/totp/enroll`: the one-time material an admin needs to add the
 * account to their authenticator app. The QR image itself is served separately by `GET
 * /api/users/me/totp/qr.png` (rendered server-side); this carries the base32 secret for manual entry and the
 * `otpauth://` URI for clients that can open it directly. It is shown once and never returned again.
 *
 * @property secret the raw base32 secret, for manual entry into an authenticator app
 * @property otpauthUri the `otpauth://totp/...` URI (also the content of the QR code)
 */
data class TotpEnrollmentDto(
    val secret: String,
    val otpauthUri: String
)
