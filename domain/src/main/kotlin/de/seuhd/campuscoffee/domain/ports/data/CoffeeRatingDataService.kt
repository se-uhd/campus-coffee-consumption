package de.seuhd.campuscoffee.domain.ports.data

import de.seuhd.campuscoffee.domain.model.CoffeeRating
import java.time.LocalDateTime
import java.util.UUID

/**
 * Port interface for coffee-rating data operations, implemented by the data layer over the projected
 * `coffee_ratings` read table. Extends the generic [CrudDataService] and adds the current-window vote
 * lookup the rating throttle relies on.
 */
interface CoffeeRatingDataService : CrudDataService<CoffeeRating, UUID> {
    /**
     * Returns the user's vote that belongs to the current cup window (its [CoffeeRating.createdAt] at or
     * after [windowStart]), or null when the user has not yet voted in this window. At most one exists (the
     * service enforces one vote per window), and the most recent is returned defensively.
     *
     * @param userId the owner of the vote
     * @param windowStart the start of the current cup's window (the cancellable increment's creation time)
     * @return the current window's vote, or null
     */
    fun findCurrentWindowVote(
        userId: UUID,
        windowStart: LocalDateTime
    ): CoffeeRating?
}
