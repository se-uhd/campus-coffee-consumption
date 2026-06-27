package de.seuhd.campuscoffee.api.security

/**
 * The credentials carried inside the encrypted login payload. This is an internal type (never a wire DTO):
 * the client serializes it as the JWE plaintext, and the controller hands it straight to the
 * authentication manager.
 *
 * @property loginName the admin's login name.
 * @property password the admin's password.
 */
data class LoginCredentials(
    val loginName: String,
    val password: String
)
