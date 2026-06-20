package de.seuhd.campuscoffee.api.dtos

import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size

/**
 * Request body for an admin absolute count override (`PUT /api/users/{id}/consumption`, edit mode): the
 * new, non-negative [total] to set directly (a [total] of zero is the reset after payment), plus an
 * optional [note] documenting the reason, recorded with the change in the event log.
 */
data class ConsumptionOverrideDto(
    @field:NotNull(message = "Total is required.")
    @field:Min(value = 0, message = "Total must not be negative.")
    val total: Int?,
    @field:Size(max = 500, message = "Note must be at most 500 characters long.")
    val note: String? = null
)
