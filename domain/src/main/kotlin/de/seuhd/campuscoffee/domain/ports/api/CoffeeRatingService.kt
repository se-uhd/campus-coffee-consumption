package de.seuhd.campuscoffee.domain.ports.api

import de.seuhd.campuscoffee.domain.exceptions.ConflictException
import de.seuhd.campuscoffee.domain.exceptions.ForbiddenException
import de.seuhd.campuscoffee.domain.exceptions.ValidationException
import de.seuhd.campuscoffee.domain.model.CancellableIncrement
import de.seuhd.campuscoffee.domain.model.CoffeeRating
import de.seuhd.campuscoffee.domain.model.CoffeeRatingPrompt
import de.seuhd.campuscoffee.domain.model.User
import java.time.LocalDateTime
import java.util.UUID

/**
 * Service interface for coffee ratings. A port implemented by the domain and consumed by the API.
 *
 * A user rates the beans they are drinking right after adding a coffee. Votes accumulate per bean over time,
 * but at most one vote per cancellable window: within that window a repeat call updates the same vote. The
 * window and the current vote are resolved by time, so no per-cup or event-store identity is stored on a
 * rating (see [CoffeeRating]).
 */
interface CoffeeRatingService {
    /**
     * Casts or updates the calling user's vote for the current cup window on the given bean.
     *
     * @param userId the owner of the vote (the drinker)
     * @param beanId the bean being rated (a merged bean resolves to its canonical target)
     * @param value the rating value, one to five
     * @param actingUser the authenticated user (must be the owner and active)
     * @return the cast or updated vote
     * @throws ForbiddenException if [actingUser] is not the owner or is deactivated
     * @throws ValidationException if [value] is outside one to five
     * @throws ConflictException if there is no cancellable cup to rate (none, or the grace window has passed)
     */
    fun rateCurrentBean(
        userId: UUID,
        beanId: UUID,
        value: Int,
        actingUser: User
    ): CoffeeRating

    /**
     * Deletes the user's vote for the given window, if any (called when the cup for that window is undone).
     *
     * @param userId the owner of the vote
     * @param windowStart the undone cup's window start (the reversed increment's creation time)
     */
    fun clearVoteInWindow(
        userId: UUID,
        windowStart: LocalDateTime
    )

    /**
     * Builds the rating prompt for the landing summary, reusing the already-computed cancellable increment so
     * it does not re-walk the log.
     *
     * @param userId the user whose prompt to build
     * @param cancellableIncrement the user's current cancellable increment, or null if none
     * @return the prompt (whether rating is possible, the default bean, and any current vote value)
     */
    fun promptFor(
        userId: UUID,
        cancellableIncrement: CancellableIncrement?
    ): CoffeeRatingPrompt

    /** Clears all ratings (dev/test reset only; destructive). */
    fun clear()
}
