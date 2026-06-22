package de.seuhd.campuscoffee.api.dtos

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size

/**
 * Request body for an admin absolute count override (`PUT /api/users/{id}/consumption`): the new,
 * non-negative [total] to set directly (any value, an admin correction), plus an optional [note]
 * documenting the reason, recorded with the change in the event log.
 */
data class ConsumptionOverrideDto(
    @field:NotNull(message = "Total is required.")
    @field:Min(value = 0, message = "Total must not be negative.")
    @field:Max(value = MAX_TOTAL, message = "Total must not exceed $MAX_TOTAL.")
    val total: Int?,
    @field:Size(max = 500, message = "Note must be at most 500 characters long.")
    val note: String? = null
) {
    private companion object {
        // A sane upper bound for an admin count correction, symmetric with the money/weight bounds; a
        // realistic running coffee count is never anywhere near this, so it only rejects nonsensical input.
        private const val MAX_TOTAL = 1_000_000L
    }
}
