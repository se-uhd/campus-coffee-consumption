package de.seuhd.campuscoffee.domain.model

import java.time.LocalDateTime

/**
 * One entry of the global price history, reconstructed from a price event in the log: the price
 * [amountCents] that took effect, when the change was recorded ([createdAt]), and the admin who made it
 * ([createdBy]). A read-only projection with no identifier. All money is in euro cents.
 */
data class PriceChange(
    val amountCents: Int,
    val createdAt: LocalDateTime,
    val createdBy: String
)
