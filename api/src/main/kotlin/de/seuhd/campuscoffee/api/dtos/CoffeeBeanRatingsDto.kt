package de.seuhd.campuscoffee.api.dtos

import java.time.LocalDateTime
import java.util.UUID

/**
 * Response DTO for one bean-rating row: the bean ([beanId], [name]), its [averageValue] (null with no
 * votes), the [voteCount], and the [latestRatingAt] / [latestPurchaseAt] timestamps.
 */
data class CoffeeBeanRatingsDto(
    val beanId: UUID,
    val name: String,
    val averageValue: Double? = null,
    val voteCount: Int,
    val latestRatingAt: LocalDateTime? = null,
    val latestPurchaseAt: LocalDateTime? = null
)
