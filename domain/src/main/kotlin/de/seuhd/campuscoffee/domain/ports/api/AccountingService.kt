package de.seuhd.campuscoffee.domain.ports.api

import de.seuhd.campuscoffee.domain.exceptions.ForbiddenException
import de.seuhd.campuscoffee.domain.exceptions.NotFoundException
import de.seuhd.campuscoffee.domain.model.ActivityEntry
import de.seuhd.campuscoffee.domain.model.User
import de.seuhd.campuscoffee.domain.model.UserBalance
import de.seuhd.campuscoffee.domain.model.UserSummary
import java.util.UUID

/**
 * Service interface for the read-side money views: per-member balances and unified activity feeds, and the
 * communal kitty. A port implemented by the domain and consumed by the API. It composes the price,
 * consumption, expense, and payment data so a member's landing page (and an admin's views) read in one go.
 */
interface AccountingService {
    /**
     * Returns everything a member's landing page needs: the count, current price, balance, kitty balance,
     * whether the most recent coffee is still cancellable, and the first page of the unified activity
     * (newest first). Readable by the member themselves or an admin.
     *
     * The summary is the member-serving view, so its activity never carries the kitty-funded portion of an
     * admin split purchase: a member's purchases read as 100% private, and the kitty split is not leaked to
     * them even when an admin recorded the split against them.
     *
     * @param userId      the member whose summary to read
     * @param activityLimit the number of activity entries on the first page
     * @param activityOffset the number of newest entries to skip
     * @param actingUser  the authenticated user attempting the read
     * @throws ForbiddenException if [actingUser] is neither that member nor an admin
     * @throws NotFoundException if no member exists for [userId]
     */
    fun userSummary(
        userId: UUID,
        activityLimit: Int,
        activityOffset: Int,
        actingUser: User
    ): UserSummary

    /**
     * Returns a page of a member's unified activity, newest first. Readable by the member or an admin.
     *
     * [includeKittyPortion] gates the kitty-funded portion of a split bean purchase
     * ([ActivityEntry.kittyAmountCents]): the admin-by-id read passes `true` (the admin sees the split), the
     * member-serving read passes `false` (the kitty split is stripped, so it never reaches a member, even for
     * an admin-recorded split purchase on them). It never changes the balance math, which uses only the
     * private portion.
     *
     * @param userId              the member whose activity to read
     * @param limit               the maximum number of entries to return
     * @param offset              the number of newest entries to skip
     * @param actingUser          the authenticated user attempting the read
     * @param includeKittyPortion whether to expose the kitty portion of a split expense (admin view only)
     * @throws ForbiddenException if [actingUser] is neither that member nor an admin
     * @throws NotFoundException if no member exists for [userId]
     */
    fun userActivity(
        userId: UUID,
        limit: Int,
        offset: Int,
        actingUser: User,
        includeKittyPortion: Boolean = false
    ): List<ActivityEntry>

    /**
     * Returns the current communal kitty balance in euro cents. Readable by any member (it is shown,
     * read-only, on the landing page).
     *
     * @return the kitty balance in euro cents
     */
    fun kittyBalanceCents(): Long

    /**
     * Returns a page of the kitty history (its individual movements), newest first. Admin-only, since the
     * movements reveal who settled and contributed.
     *
     * @param limit      the maximum number of entries to return
     * @param offset     the number of newest entries to skip
     * @param actingUser the authenticated user attempting the read
     * @throws ForbiddenException if [actingUser] is not an admin
     */
    fun kittyHistory(
        limit: Int,
        offset: Int,
        actingUser: User
    ): List<ActivityEntry>

    /**
     * Returns every member's current count and balance, for the admin overview. Admin-only.
     *
     * @param actingUser the authenticated user attempting the read
     * @throws ForbiddenException if [actingUser] is not an admin
     */
    fun allBalances(actingUser: User): List<UserBalance>
}
