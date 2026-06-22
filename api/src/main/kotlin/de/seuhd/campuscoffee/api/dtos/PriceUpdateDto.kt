package de.seuhd.campuscoffee.api.dtos

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotNull

/**
 * Request body for setting the global coffee price (`PUT /api/price`, admin only): the new, non-negative
 * price per cup in euro cents.
 */
data class PriceUpdateDto(
    @field:NotNull(message = "Amount is required.")
    @field:Min(value = 0, message = "Amount must not be negative.")
    @field:Max(value = MAX_MONEY_CENTS, message = "Amount is implausibly large.")
    val amountCents: Int?
)
