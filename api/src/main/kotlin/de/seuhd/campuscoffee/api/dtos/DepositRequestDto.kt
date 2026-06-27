package de.seuhd.campuscoffee.api.dtos

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.util.UUID

/**
 * Request body for an admin recording that a user paid money into the kitty
 * (`POST /api/kitty/deposit`): the user, the positive amount in euro cents, and an optional note.
 */
data class DepositRequestDto(
    @field:NotNull(message = "User id is required.")
    val userId: UUID?,
    @field:NotNull(message = "Amount is required.")
    @field:Min(value = 1, message = "A deposit amount must be positive.")
    @field:Max(value = MAX_MONEY_CENTS, message = "Amount is implausibly large.")
    val amountCents: Int?,
    @field:Size(max = 500, message = "Note must be at most 500 characters long.")
    val note: String? = null
)
