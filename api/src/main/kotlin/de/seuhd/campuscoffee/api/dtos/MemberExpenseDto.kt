package de.seuhd.campuscoffee.api.dtos

import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size

/**
 * Request body for a member recording their own bean purchase (`POST /api/expenses`). It carries no buyer
 * or split: the server attributes the purchase to the calling member and books it 100% from their own
 * pocket, so a member cannot attribute it to someone else or fund it from the kitty.
 */
data class MemberExpenseDto(
    @field:NotNull(message = "Weight is required.")
    @field:Min(value = 0, message = "Weight must not be negative.")
    val weightGrams: Int?,
    @field:NotNull(message = "Amount is required.")
    @field:Min(value = 1, message = "Amount must be positive.")
    val amountCents: Int?,
    @field:Size(max = 500, message = "Note must be at most 500 characters long.")
    val note: String? = null
)
