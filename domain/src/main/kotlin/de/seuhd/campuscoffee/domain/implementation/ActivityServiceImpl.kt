package de.seuhd.campuscoffee.domain.implementation

import de.seuhd.campuscoffee.domain.exceptions.ConflictException
import de.seuhd.campuscoffee.domain.model.ActivityEntry
import de.seuhd.campuscoffee.domain.model.GlobalActivityEntry
import de.seuhd.campuscoffee.domain.model.User
import de.seuhd.campuscoffee.domain.model.persistedId
import de.seuhd.campuscoffee.domain.ports.api.ActivityService
import de.seuhd.campuscoffee.domain.ports.data.ActivityDataService
import de.seuhd.campuscoffee.domain.ports.data.UserDataService
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Domain implementation of [ActivityService]. Surfaces the event-log walk (via [ActivityDataService]) as the
 * three chronological feeds: a user's unified activity, the kitty history, and the admin global activity.
 * The user and kitty feeds page the same walk that backs the summary; the global feed additionally resolves
 * each row's subject user to a login (in the data layer) and a display name (here). It does not own any
 * balance number; those stay on [de.seuhd.campuscoffee.domain.ports.api.AccountingService].
 */
@Service
class ActivityServiceImpl(
    private val activityDataService: ActivityDataService,
    private val userDataService: UserDataService
) : ActivityService {
    override fun userActivity(
        userId: UUID,
        limit: Int,
        offset: Int,
        actingUser: User,
        includeKittyPortion: Boolean
    ): List<ActivityEntry> {
        val user = requireMayViewUser(userId, actingUser, userDataService)
        val page = pageNewestFirst(activityDataService.userActivity(userId, user.loginName), limit, offset)
        // the admin-by-id read keeps the kitty split; the user-serving read strips it so the kitty-funded
        // portion of a split purchase never reaches a user (the balance math is unaffected either way)
        return if (includeKittyPortion) page else page.map { it.withoutKittyPortion() }
    }

    override fun kittyHistory(
        limit: Int,
        offset: Int,
        actingUser: User
    ): List<ActivityEntry> {
        requireAdmin(actingUser)
        return pageNewestFirst(activityDataService.kittyHistory(), limit, offset)
    }

    override fun globalActivity(
        limit: Int,
        offset: Int,
        actingUser: User
    ): List<GlobalActivityEntry> {
        requireAdmin(actingUser)
        return pageNewestFirst(resolvedGlobalActivity(), limit, offset)
    }

    override fun globalActivityForExport(actingUser: User): List<GlobalActivityEntry> {
        requireAdmin(actingUser)
        // unpaged, newest-first: the export must be complete, so it is never clamped to a page size. It is
        // bounded only by a generous safety cap: rather than silently truncate (which would drop history) or
        // risk exhausting heap on a pathologically large log, an over-cap export fails with a clear message.
        val export = resolvedGlobalActivity().asReversed()
        if (export.size > EXPORT_MAX_ROWS) {
            throw ConflictException(
                "The activity log is too large to export as a single CSV " +
                    "(${export.size} rows exceed the $EXPORT_MAX_ROWS-row limit); use the paged activity view."
            )
        }
        return export
    }

    /**
     * Reads the whole global activity feed once (via the data port, which resolves each subject's login from
     * the log) and enriches each row's subject with a display name, leaving the result oldest-first, the order
     * the walk produces, which the callers then page or reverse. A row whose subject is not a current user (a
     * hard-deleted user whose events outlive their user row) is labeled [DELETED_USER_NAME]; a row with no
     * subject (a kitty adjustment, a price change) is left as-is.
     */
    private fun resolvedGlobalActivity(): List<GlobalActivityEntry> {
        val nameById = userDataService.getAll().associate { it.persistedId to "${it.firstName} ${it.lastName}" }
        return activityDataService.globalActivity().map { entry ->
            val subjectId = entry.subjectUserId ?: return@map entry
            entry.copy(subjectName = nameById[subjectId] ?: DELETED_USER_NAME)
        }
    }

    private companion object {
        private const val DELETED_USER_NAME = "(deleted user)"

        // a generous safety cap on the single-file CSV export so a pathologically large log fails loudly
        // rather than silently truncating or exhausting heap; far above any realistic SE@UHD log size
        private const val EXPORT_MAX_ROWS = 100_000
    }
}
