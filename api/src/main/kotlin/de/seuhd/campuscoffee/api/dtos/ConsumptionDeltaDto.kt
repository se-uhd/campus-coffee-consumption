package de.seuhd.campuscoffee.api.dtos

import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotNull

/**
 * Request body for the admin single-step coffee count change (`POST /api/users/{userId}/consumption`): a
 * [delta] of exactly `+1` or `-1`. The member endpoint (`POST /api/consumption`) takes no body and always
 * adds one, so this DTO is admin-only. Any other adjustment goes through the admin absolute override
 * (`PUT`). The `@Min`/`@Max` bound the range and [deltaIsSingleStep] rejects the in-range `0`, so a `0` or
 * out-of-range value yields a 400 at the validation layer (the domain enforces the same rule as a backstop).
 */
data class ConsumptionDeltaDto(
    @field:NotNull(message = "Delta is required.")
    @field:Min(value = -1, message = "Delta must be +1 or -1.")
    @field:Max(value = 1, message = "Delta must be +1 or -1.")
    val delta: Int?
) {
    /**
     * Rejects the in-range `0` that `@Min(-1)`/`@Max(1)` alone would admit; a null [delta] is left to
     * `@NotNull`. Exposed as a getter so bean validation evaluates it.
     */
    @get:AssertTrue(message = "Delta must be +1 or -1.")
    val deltaIsSingleStep: Boolean
        get() = delta == null || delta == 1 || delta == -1
}
