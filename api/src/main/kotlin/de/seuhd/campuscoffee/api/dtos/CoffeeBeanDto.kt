package de.seuhd.campuscoffee.api.dtos

import java.util.UUID

/**
 * Response DTO for a coffee bean: its [id], display [name], and whether it is [active] (a merged bean is
 * inactive). The selectable-bean list and the rename/merge responses use this shape.
 */
data class CoffeeBeanDto(
    val id: UUID,
    val name: String,
    val active: Boolean
)
