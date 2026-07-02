package de.seuhd.campuscoffee.api.dtos

import de.seuhd.campuscoffee.domain.model.ExpenseType
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size

/**
 * Request body for a user recording their own outlay (`POST /api/expenses`). It carries no buyer or split:
 * the server attributes it to the calling user and books it 100% from their own pocket, so a user cannot
 * attribute it to someone else or fund it from the kitty. For a `BEANS` outlay the [beanName] and
 * [weightGrams] are required; for `OTHER` they must be omitted (the domain service enforces the combination).
 */
data class OwnExpenseDto(
    @field:NotNull(message = "An expense type is required.")
    val expenseType: ExpenseType?,
    @field:Size(max = 200, message = "A bean name must be at most 200 characters long.")
    val beanName: String? = null,
    @field:Min(value = MIN_WEIGHT_GRAMS, message = "Weight must be at least 100 grams.")
    @field:Max(value = MAX_WEIGHT_GRAMS, message = "Weight is implausibly large.")
    val weightGrams: Int? = null,
    @field:NotNull(message = "Amount is required.")
    @field:Min(value = 1, message = "Amount must be positive.")
    @field:Max(value = MAX_MONEY_CENTS, message = "Amount is implausibly large.")
    val amountCents: Int?,
    @field:Size(max = 500, message = "Note must be at most 500 characters long.")
    val note: String? = null
)
