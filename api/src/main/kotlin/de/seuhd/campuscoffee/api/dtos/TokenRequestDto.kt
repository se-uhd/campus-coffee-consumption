package de.seuhd.campuscoffee.api.dtos

import jakarta.validation.constraints.NotBlank

/**
 * Request body for `POST /api/auth/token`: the credentials to exchange for a bearer token.
 */
data class TokenRequestDto(
    @field:NotBlank(message = "Login name cannot be empty.")
    val loginName: String?,
    @field:NotBlank(message = "Password cannot be empty.")
    val password: String?
)
