package de.seuhd.campuscoffee.domain.ports.api

import de.seuhd.campuscoffee.domain.exceptions.ForbiddenException
import de.seuhd.campuscoffee.domain.exceptions.NotFoundException
import de.seuhd.campuscoffee.domain.model.User
import de.seuhd.campuscoffee.domain.model.UserBalance
import de.seuhd.campuscoffee.domain.model.UserSummary
import java.util.UUID

/**
 * Service interface for the read-side money **numbers**: a user's landing summary, the communal kitty
 * balance, and the per-user overview. A port implemented by the domain and consumed by the API. The
 * chronological feeds (a user's activity, the kitty history, the global activity) live on
 * [ActivityService]; this service owns the balance, count, and overview figures.
 */
interface AccountingService {
    /**
     * Returns everything a user's landing page needs: the count, current price, balance, kitty balance,
     * whether the most recent coffee is still cancellable, and the first page of the unified activity
     * (newest first). Readable by the user themselves or an admin.
     *
     * The summary is the user-serving view, so its activity never carries the kitty-funded portion of an
     * admin split purchase: a user's purchases read as 100% private, and the kitty split is not leaked to
     * them even when an admin recorded the split against them.
     *
     * @param userId      the user whose summary to read
     * @param activityLimit the number of activity entries on the first page
     * @param activityOffset the number of newest entries to skip
     * @param actingUser  the authenticated user attempting the read
     * @throws ForbiddenException if [actingUser] is neither that user nor an admin
     * @throws NotFoundException if no user exists for [userId]
     */
    fun userSummary(
        userId: UUID,
        activityLimit: Int,
        activityOffset: Int,
        actingUser: User
    ): UserSummary

    /**
     * Returns the current communal kitty balance in euro cents. Readable by any user (it is shown,
     * read-only, on the landing page).
     *
     * @return the kitty balance in euro cents
     */
    fun kittyBalanceCents(): Long

    /**
     * Returns every user's current count and balance, for the admin overview. Admin-only.
     *
     * @param actingUser the authenticated user attempting the read
     * @throws ForbiddenException if [actingUser] is not an admin
     */
    fun allBalances(actingUser: User): List<UserBalance>
}
