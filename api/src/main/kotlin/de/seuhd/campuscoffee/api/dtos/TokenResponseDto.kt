package de.seuhd.campuscoffee.api.dtos

/**
 * Response body for `POST /api/auth/token`: the issued JWT bearer token, and whether the admin still needs
 * to enroll a second factor.
 *
 * @property token the issued JWT bearer token
 * @property enrollmentRequired true when the admin has not enrolled a second factor yet, so the token is an
 *   enrollment-only session and the SPA routes them to the enrollment page
 */
data class TokenResponseDto(
    val token: String,
    val enrollmentRequired: Boolean = false
)
