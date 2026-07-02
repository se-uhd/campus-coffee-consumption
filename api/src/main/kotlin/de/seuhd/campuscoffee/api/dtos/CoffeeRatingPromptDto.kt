package de.seuhd.campuscoffee.api.dtos

import java.util.UUID

/**
 * The rating prompt embedded in the landing summary: whether the user may rate now ([canRate]), the bean to
 * preselect ([defaultBeanId], null when there is none), and the current window's already-cast rating
 * ([value], null if not yet rated).
 */
data class CoffeeRatingPromptDto(
    val canRate: Boolean,
    val defaultBeanId: UUID? = null,
    val value: Int? = null
)
