package de.seuhd.campuscoffee.api.dtos

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotNull
import java.util.UUID

/**
 * Request body for rating the current cup's beans (`PUT /api/consumption/rating`): the [beanId] to rate and
 * the [value] from one to five.
 */
data class RatingRequestDto(
    @field:NotNull(message = "A bean is required.")
    val beanId: UUID?,
    @field:NotNull(message = "A rating value is required.")
    @field:Min(value = 1, message = "A rating must be at least one.")
    @field:Max(value = 5, message = "A rating must be at most five.")
    val value: Int?
)
