package de.seuhd.campuscoffee.domain.model

import java.time.LocalDateTime

/**
 * A read-only rating row for one canonical [bean]: the [averageValue] of its votes (null when it has none),
 * the [voteCount] (the number of votes), and the [latestRatingAt] / [latestPurchaseAt] timestamps. Votes and
 * purchases recorded against a bean that was later merged are counted under the canonical target. Derived on
 * read (not event-sourced), like [UserSummary].
 */
data class CoffeeBeanRatings(
    val bean: CoffeeBean,
    val averageValue: Double?,
    val voteCount: Int,
    val latestRatingAt: LocalDateTime?,
    val latestPurchaseAt: LocalDateTime?
)
