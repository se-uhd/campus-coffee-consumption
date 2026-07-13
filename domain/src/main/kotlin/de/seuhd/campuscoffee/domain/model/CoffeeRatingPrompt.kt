package de.seuhd.campuscoffee.domain.model

import java.util.UUID

/**
 * A read-only projection returned in the landing summary and the add-coffee response, telling the SPA
 * whether to show the rating controls and how to prefill them.
 *
 * @property canRate whether the user may rate right now (they have a cancellable cup within its grace window)
 * @property defaultBeanId the bean to preselect: the current window's already-cast vote, else the bean of the
 *   most recent rating by anyone (resolved to its canonical bean if that bean was since merged away), else the
 *   most recently purchased bean, else null (the SPA then picks the first selectable bean)
 * @property value the current window's already-cast rating value (one to five), or null if not yet rated
 */
data class CoffeeRatingPrompt(
    val canRate: Boolean,
    val defaultBeanId: UUID?,
    val value: Int?
)
