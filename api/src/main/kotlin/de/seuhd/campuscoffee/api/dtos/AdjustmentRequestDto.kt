package de.seuhd.campuscoffee.api.dtos

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size

/**
 * Request body for an admin adjusting the kitty without a member (`POST /api/kitty/adjustment`): the
 * signed amount in euro cents (an initial float, or a correction that may be negative) and an optional note.
 * The service rejects a zero amount; the magnitude is bounded on both sides as a fat-finger guardrail (the
 * service still enforces that the adjustment cannot drive the kitty balance negative).
 */
data class AdjustmentRequestDto(
    @field:NotNull(message = "Amount is required.")
    @field:Min(value = -MAX_MONEY_CENTS, message = "Amount is implausibly large.")
    @field:Max(value = MAX_MONEY_CENTS, message = "Amount is implausibly large.")
    val amountCents: Int?,
    @field:Size(max = 500, message = "Note must be at most 500 characters long.")
    val note: String? = null
)
