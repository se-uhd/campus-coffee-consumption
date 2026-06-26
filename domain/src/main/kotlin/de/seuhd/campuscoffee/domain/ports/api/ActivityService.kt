package de.seuhd.campuscoffee.domain.ports.api

import de.seuhd.campuscoffee.domain.exceptions.ForbiddenException
import de.seuhd.campuscoffee.domain.exceptions.NotFoundException
import de.seuhd.campuscoffee.domain.model.ActivityEntry
import de.seuhd.campuscoffee.domain.model.GlobalActivityEntry
import de.seuhd.campuscoffee.domain.model.User
import java.util.UUID

/**
 * Service interface for the read-side **activity feeds**: a member's unified activity, the communal kitty
 * history, and the admin-only global activity across everyone. A port implemented by the domain and consumed
 * by the API. It is the feed counterpart of [AccountingService] (which owns the balance, summary, and overview
 * numbers); both read the same event-log walk, here surfaced as chronological lists with running balances.
 */
interface ActivityService {
    /**
     * Returns a page of a member's unified activity, newest first. Readable by the member or an admin.
     *
     * [includeKittyPortion] gates the kitty-funded portion of a split bean purchase
     * ([ActivityEntry.kittyAmountCents]): the admin-by-id read passes `true` (the admin sees the split), the
     * member-serving read passes `false` (the kitty split is stripped, so it never reaches a member, even for
     * an admin-recorded split purchase on them). It never changes the balance math, which uses only the
     * private portion.
     *
     * @param userId the member whose activity to read
     * @param limit the maximum number of entries to return
     * @param offset the number of newest entries to skip
     * @param actingUser the authenticated user attempting the read
     * @param includeKittyPortion whether to expose the kitty portion of a split expense (admin view only)
     * @throws ForbiddenException if [actingUser] is neither that member nor an admin
     * @throws NotFoundException if no member exists for [userId]
     */
    fun memberActivity(
        userId: UUID,
        limit: Int,
        offset: Int,
        actingUser: User,
        includeKittyPortion: Boolean = false
    ): List<ActivityEntry>

    /**
     * Returns a page of the kitty history (its individual movements), newest first. Admin-only, since the
     * movements reveal who settled and contributed.
     *
     * @param limit the maximum number of entries to return
     * @param offset the number of newest entries to skip
     * @param actingUser the authenticated user attempting the read
     * @throws ForbiddenException if [actingUser] is not an admin
     */
    fun kittyHistory(
        limit: Int,
        offset: Int,
        actingUser: User
    ): List<ActivityEntry>

    /**
     * Returns a page of the whole-installation activity feed, newest first: every coffee, expense, deposit,
     * kitty adjustment, and price change across all members, each row carrying the subject member, the actor,
     * and the member and kitty running balances the event moved. Admin-only.
     *
     * @param limit the maximum number of entries to return
     * @param offset the number of newest entries to skip
     * @param actingUser the authenticated user attempting the read
     * @throws ForbiddenException if [actingUser] is not an admin
     */
    fun globalActivity(
        limit: Int,
        offset: Int,
        actingUser: User
    ): List<GlobalActivityEntry>

    /**
     * Returns the entire global activity feed, newest first and **unpaged**, for the CSV export. Admin-only.
     * Unlike [globalActivity] this is not capped at a page size: it returns every row so the export is
     * complete. It must never truncate (a truncating cap would corrupt the running balances), so on a
     * pathologically large log it is preferable to fail rather than emit a partial tail.
     *
     * @param actingUser the authenticated user attempting the read
     * @throws ForbiddenException if [actingUser] is not an admin
     */
    fun globalActivityForExport(actingUser: User): List<GlobalActivityEntry>
}
