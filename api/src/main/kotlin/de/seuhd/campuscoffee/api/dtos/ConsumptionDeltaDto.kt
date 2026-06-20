package de.seuhd.campuscoffee.api.dtos

import jakarta.validation.constraints.NotNull

/**
 * Request body for a single-step coffee count change (`POST /api/consumption` and the admin equivalent):
 * a [delta] of exactly `+1` or `-1`. Any other adjustment goes through the admin absolute override
 * (`PUT`), so the member-facing change is always one coffee up or down. The exact `+1`/`-1` rule is
 * checked in the domain so a `0` or out-of-range value yields a 400.
 */
data class ConsumptionDeltaDto(
    @field:NotNull(message = "Delta is required.")
    val delta: Int?
)
