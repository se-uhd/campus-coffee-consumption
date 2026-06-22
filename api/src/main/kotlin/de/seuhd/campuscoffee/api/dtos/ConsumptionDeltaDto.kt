package de.seuhd.campuscoffee.api.dtos

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotNull

/**
 * Request body for the admin single-step coffee count change (`POST /api/users/{userId}/consumption`): a
 * [delta] of exactly `+1` or `-1`. The member endpoint (`POST /api/consumption`) takes no body and always
 * adds one, so this DTO is admin-only. Any other adjustment goes through the admin absolute override
 * (`PUT`). The `@Min`/`@Max` bound the range for the API docs; the exact `+1`/`-1` rule (rejecting `0`) is
 * enforced in the domain, so a `0` or out-of-range value yields a 400.
 */
data class ConsumptionDeltaDto(
    @field:NotNull(message = "Delta is required.")
    @field:Min(value = -1, message = "Delta must be +1 or -1.")
    @field:Max(value = 1, message = "Delta must be +1 or -1.")
    val delta: Int?
)
