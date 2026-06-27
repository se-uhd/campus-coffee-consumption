package de.seuhd.campuscoffee.api.dtos

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size

/**
 * Request body for an admin recording or correcting a bean purchase for a user
 * (`POST`/`PUT /api/users/{userId}/expenses`). The buyer is the `{userId}` path variable; the body carries
 * the weight, total, and the kitty/private split (which the service requires to sum to the total).
 */
data class AdminExpenseDto(
    @field:NotNull(message = "Weight is required.")
    @field:Min(value = MIN_WEIGHT_GRAMS, message = "Weight must be at least 100 grams.")
    @field:Max(value = MAX_WEIGHT_GRAMS, message = "Weight is implausibly large.")
    val weightGrams: Int?,
    @field:NotNull(message = "Amount is required.")
    @field:Min(value = 1, message = "Amount must be at least one cent.")
    @field:Max(value = MAX_MONEY_CENTS, message = "Amount is implausibly large.")
    val amountCents: Int?,
    @field:NotNull(message = "Private amount is required.")
    @field:Min(value = 0, message = "Private amount must not be negative.")
    @field:Max(value = MAX_MONEY_CENTS, message = "Private amount is implausibly large.")
    val privateAmountCents: Int?,
    @field:NotNull(message = "Kitty amount is required.")
    @field:Min(value = 0, message = "Kitty amount must not be negative.")
    @field:Max(value = MAX_MONEY_CENTS, message = "Kitty amount is implausibly large.")
    val kittyAmountCents: Int?,
    @field:Size(max = 500, message = "Note must be at most 500 characters long.")
    val note: String? = null
)
