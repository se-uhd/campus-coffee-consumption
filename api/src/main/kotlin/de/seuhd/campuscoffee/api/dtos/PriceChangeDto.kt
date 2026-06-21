package de.seuhd.campuscoffee.api.dtos

import java.time.LocalDateTime

/**
 * Response DTO for one entry of the price history: the price [amountCents] (euro cents) that took effect,
 * when the change was recorded ([createdAt]), and the admin who made it ([createdBy]).
 */
data class PriceChangeDto(
    val amountCents: Int,
    val createdAt: LocalDateTime,
    val createdBy: String
)
