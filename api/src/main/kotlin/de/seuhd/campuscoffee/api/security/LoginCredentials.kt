package de.seuhd.campuscoffee.api.security

/**
 * The credentials carried inside the encrypted login payload. This is an internal type (never a wire DTO):
 * the client serializes it as the JWE plaintext, and the controller hands it straight to the
 * authentication manager.
 *
 * @property loginName the admin's login name.
 * @property password the admin's password.
 * @property totp the admin's current 6-digit authenticator code, if supplied. Optional: an admin who has
 *   not yet enrolled a second factor omits it, and an enrolled admin must supply a valid one.
 */
data class LoginCredentials(
    val loginName: String,
    val password: String,
    val totp: String? = null
)
