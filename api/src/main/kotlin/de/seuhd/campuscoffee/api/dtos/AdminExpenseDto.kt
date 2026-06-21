package de.seuhd.campuscoffee.api.dtos

import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size

/**
 * Request body for an admin recording or correcting a bean purchase for a member
 * (`POST`/`PUT /api/users/{userId}/expenses`). The buyer is the `{userId}` path variable; the body carries
 * the weight, total, and the kitty/private split (which the service requires to sum to the total).
 */
data class AdminExpenseDto(
    @field:NotNull(message = "Weight is required.")
    @field:Min(value = 0, message = "Weight must not be negative.")
    val weightGrams: Int?,
    @field:NotNull(message = "Amount is required.")
    @field:Min(value = 0, message = "Amount must not be negative.")
    val amountCents: Int?,
    @field:NotNull(message = "Private amount is required.")
    @field:Min(value = 0, message = "Private amount must not be negative.")
    val privateAmountCents: Int?,
    @field:NotNull(message = "Kitty amount is required.")
    @field:Min(value = 0, message = "Kitty amount must not be negative.")
    val kittyAmountCents: Int?,
    @field:Size(max = 500, message = "Note must be at most 500 characters long.")
    val note: String? = null
)
