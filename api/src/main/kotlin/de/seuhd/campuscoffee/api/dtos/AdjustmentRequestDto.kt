package de.seuhd.campuscoffee.api.dtos

import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size

/**
 * Request body for an admin adjusting the kitty without a member (`POST /api/payments/adjustment`): the
 * signed amount in euro cents (an initial float, or a correction that may be negative) and an optional note.
 * The service rejects a zero amount.
 */
data class AdjustmentRequestDto(
    @field:NotNull(message = "Amount is required.")
    val amountCents: Int?,
    @field:Size(max = 500, message = "Note must be at most 500 characters long.")
    val note: String? = null
)
