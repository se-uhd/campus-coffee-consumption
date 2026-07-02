package de.seuhd.campuscoffee.api.dtos

import jakarta.validation.constraints.NotNull
import java.util.UUID

/**
 * Request body for merging a bean into another (`POST /api/beans/{id}/merge`, admin only): the id of the
 * canonical [targetBeanId] the path bean is merged into.
 */
data class BeanMergeDto(
    @field:NotNull(message = "A merge target is required.")
    val targetBeanId: UUID?
)
