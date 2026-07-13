package de.seuhd.campuscoffee.api.dtos

import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern

/**
 * Request body for `POST /api/users/me/totp/activate`: the current 6-digit authenticator code that confirms
 * a pending enrollment before it is switched on.
 *
 * @property code the 6-digit code from the authenticator app
 */
data class TotpActivateRequestDto(
    @field:NotNull
    @field:Pattern(regexp = "\\d{6}", message = "The authenticator code must be 6 digits.")
    val code: String?
)
