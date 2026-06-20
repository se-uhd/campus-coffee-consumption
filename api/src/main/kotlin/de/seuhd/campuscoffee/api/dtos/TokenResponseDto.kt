package de.seuhd.campuscoffee.api.dtos

/**
 * Response body for `POST /api/auth/token`: the issued JWT bearer token.
 */
data class TokenResponseDto(
    val token: String
)
