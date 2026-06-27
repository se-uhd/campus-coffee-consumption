package de.seuhd.campuscoffee.api.dtos

import com.fasterxml.jackson.annotation.JsonIgnore
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotNull

/**
 * Request body for the admin single-step coffee count change (`POST /api/users/{userId}/consumption`): a
 * [delta] of exactly `+1` or `-1`. The user endpoint (`POST /api/consumption`) takes no body and always
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
     * `@NotNull`. Exposed as a getter so bean validation evaluates it, but hidden from JSON
     * ([JsonIgnore]) and the OpenAPI schema ([Schema] `hidden`): it is a validation-only derived flag, not a
     * request field, so it must not leak into the request body contract or the generated frontend DTO.
     */
    @get:AssertTrue(message = "Delta must be +1 or -1.")
    @get:JsonIgnore
    @get:Schema(hidden = true)
    val deltaIsSingleStep: Boolean
        get() = delta == null || delta == 1 || delta == -1
}
