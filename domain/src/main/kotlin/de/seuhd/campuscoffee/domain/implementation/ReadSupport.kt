package de.seuhd.campuscoffee.domain.implementation

import de.seuhd.campuscoffee.domain.exceptions.ForbiddenException
import de.seuhd.campuscoffee.domain.model.ActivityEntry
import de.seuhd.campuscoffee.domain.model.Role
import de.seuhd.campuscoffee.domain.model.User
import de.seuhd.campuscoffee.domain.model.persistedId
import de.seuhd.campuscoffee.domain.ports.data.UserDataService
import java.util.UUID

/** The largest activity page a read service returns in one call; the per-request limit is clamped to this. */
internal const val MAX_ACTIVITY_PAGE = 100

/**
 * Reverses an oldest-first activity list (the order the walk produces) to newest-first and returns the
 * requested page, clamping the limit to [MAX_ACTIVITY_PAGE]. Shared by the member, kitty, summary, and global
 * reads, so they page identically.
 *
 * @param activity the activity oldest-first
 * @param limit the maximum number of entries to return
 * @param offset the number of newest entries to skip
 */
internal fun <T> pageNewestFirst(
    activity: List<T>,
    limit: Int,
    offset: Int
): List<T> = activity.asReversed().drop(offset.coerceAtLeast(0)).take(limit.coerceIn(0, MAX_ACTIVITY_PAGE))

/**
 * Strips both portions of a split expense from an activity entry (the member-serving views): the split is
 * admin-only and must never reach a member. A no-op for an entry that carries none.
 */
internal fun ActivityEntry.withoutKittyPortion(): ActivityEntry =
    if (kittyAmountCents == null && privateAmountCents == null) {
        this
    } else {
        copy(privateAmountCents = null, kittyAmountCents = null)
    }

/**
 * Requires [actingUser] to be an admin, else 403.
 *
 * @param actingUser the authenticated user attempting the read
 */
internal fun requireAdmin(actingUser: User) {
    if (actingUser.role != Role.ADMIN) {
        throw ForbiddenException("Only an admin may read this.")
    }
}

/**
 * Resolves the member (404 if missing) and allows the read only for the member themselves or an admin.
 *
 * @param userId the member whose data is being read
 * @param actingUser the authenticated user attempting the read
 * @param userDataService the port used to resolve the member
 */
internal fun requireMayViewUser(
    userId: UUID,
    actingUser: User,
    userDataService: UserDataService
): User {
    val user = userDataService.getById(userId)
    if (actingUser.role != Role.ADMIN && actingUser.persistedId != userId) {
        throw ForbiddenException("A member's balance may be read only by the member themselves or an admin.")
    }
    return user
}
