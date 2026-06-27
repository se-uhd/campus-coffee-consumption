package de.seuhd.campuscoffee.data.implementations
import de.seuhd.campuscoffee.data.persistence.entities.LoggedEntityType
import de.seuhd.campuscoffee.data.persistence.projection.EventProjectionType
import de.seuhd.campuscoffee.data.persistence.projection.EventReducer
import de.seuhd.campuscoffee.data.persistence.projection.PricePoint
import de.seuhd.campuscoffee.data.persistence.projection.actorOf
import de.seuhd.campuscoffee.data.persistence.projection.createdAtOf
import de.seuhd.campuscoffee.data.persistence.projection.intBody
import de.seuhd.campuscoffee.data.persistence.projection.priceAsOf
import de.seuhd.campuscoffee.data.persistence.projection.seqOf
import de.seuhd.campuscoffee.data.persistence.projection.toGlobalEntry
import de.seuhd.campuscoffee.data.persistence.projection.toKittyEntry
import de.seuhd.campuscoffee.data.persistence.projection.toUserEntry
import de.seuhd.campuscoffee.data.persistence.projection.uuidBody
import de.seuhd.campuscoffee.data.persistence.repositories.EventRepository
import de.seuhd.campuscoffee.domain.model.ActivityEntry
import de.seuhd.campuscoffee.domain.model.CancellableIncrement
import de.seuhd.campuscoffee.domain.model.GlobalActivityEntry
import de.seuhd.campuscoffee.domain.model.PriceChange
import de.seuhd.campuscoffee.domain.ports.data.ActivityDataService
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Reads the unified-activity and balance projections straight from the append-only event log (there is no
 * activity table). A single shared [EventReducer] backs all three feeds: it walks a user's bounded stream
 * for the user feed, the kitty money stream for the kitty history, and the whole log for the admin global
 * feed, valuing each coffee at [priceAsOf] its append position. Money sums use [Long]; per-event effects fit
 * [Int] before widening.
 */
@Service
class ActivityDataServiceImpl(
    private val eventRepository: EventRepository
) : ActivityDataService {
    override fun userActivity(
        userId: UUID,
        ownerLogin: String
    ): List<ActivityEntry> {
        val walk = EventReducer(loadPricePoints()) { if (it == userId) ownerLogin else null }
        return eventRepository
            .findUserActivity(userId.toString())
            .mapNotNull { walk.accept(it) }
            .filter { it.subjectUserId == userId && it.userEffect != null }
            .map { it.toUserEntry() }
    }

    override fun kittyHistory(): List<ActivityEntry> {
        // ownership is irrelevant here: the kitty stream carries no consumptions, so byOwner is never tested
        val walk = EventReducer(loadPricePoints()) { null }
        return eventRepository
            .findKittyStream()
            .mapNotNull { walk.accept(it) }
            .filter { it.kittyEffect != null }
            .map { it.toKittyEntry() }
    }

    override fun globalActivity(): List<GlobalActivityEntry> {
        // Resolve every subject's login from the log's own User events (the complete historical set, so a
        // hard-deleted user still resolves), not the current users read model. login_name is immutable, so a
        // recorded login equals the one a user's own coffee was attributed to (created_by); this keeps the
        // owner test classifying a deleted user's self-scans as CONSUMPTION/CONSUMPTION_CANCEL rather than
        // misreading them as admin overrides, and lets the row carry the user's (immutable) login.
        val subjectLoginById = loadSubjectLogins()
        val walk = EventReducer(loadPricePoints()) { subjectLoginById[it] }
        return eventRepository
            .findActivityStream()
            .mapNotNull { walk.accept(it) }
            // drop a no-op edit that moved nothing (e.g. an expense whose note or weight changed but neither
            // money portion did): it would be a meaningless row with both effect columns blank. A price change
            // legitimately moves no balance, so it is kept.
            .filter { it.userEffect != null || it.kittyEffect != null || it.kind == EventProjectionType.PRICE_CHANGE }
            .map { it.toGlobalEntry(subjectLoginById) }
    }

    override fun lastCancellableIncrement(
        userId: UUID,
        ownerLogin: String
    ): CancellableIncrement? {
        val prices = loadPricePoints()
        val stack = ArrayDeque<CancellableIncrement>()
        var prevCount = 0
        eventRepository
            .findUserActivity(userId.toString())
            .filter { LoggedEntityType.ofLabel(requireNotNull(it.entityType)) == LoggedEntityType.COFFEE_CONSUMPTION }
            .forEach { event ->
                val count = intBody(event, "count") ?: 0
                val delta = count - prevCount
                prevCount = count
                if (delta == 0) return@forEach
                val isOwnerStep = actorOf(event) == ownerLogin
                when {
                    isOwnerStep && delta == 1 ->
                        stack.addLast(
                            CancellableIncrement(createdAtOf(event), priceAsOf(seqOf(event), prices))
                        )
                    isOwnerStep && delta == -1 -> stack.removeLastOrNull()
                    // any admin step down removes that many outstanding owner increments, so a user cannot
                    // undo a cup the admin removed or one the admin added. This includes an admin single-step
                    // -1: it credits the user, so leaving their pending undo would let the same cup be
                    // credited twice. The intended effect is that an admin -1 also clears the user's undo.
                    // An admin step up adds non-undoable cups and pushes nothing.
                    delta < 0 -> repeat(minOf(-delta, stack.size)) { stack.removeLast() }
                }
            }
        return stack.lastOrNull()
    }

    override fun priceHistory(): List<PriceChange> =
        eventRepository.findByEntityTypeOrderBySeqAsc(LoggedEntityType.COFFEE_PRICE.label).map {
            PriceChange(
                amountCents = intBody(it, "amountCents") ?: 0,
                createdAt = createdAtOf(it),
                createdBy = actorOf(it)
            )
        }

    /** Loads the price timeline once, ascending by append position. */
    private fun loadPricePoints(): List<PricePoint> =
        eventRepository
            .findByEntityTypeOrderBySeqAsc(LoggedEntityType.COFFEE_PRICE.label)
            .map { PricePoint(seqOf(it), intBody(it, "amountCents") ?: 0) }

    /**
     * The login of every user that ever existed, by id, read from the log's `User` events so a hard-deleted
     * user still resolves. A `User` event body carries the immutable `loginName`; a DELETE body carries only
     * the id, so it is skipped (`loginName` absent) and the map keeps the login from the create. Used by the
     * global walk for the owner test and to stamp each row's subject login.
     */
    private fun loadSubjectLogins(): Map<UUID, String> =
        eventRepository
            .findByEntityTypeOrderBySeqAsc(LoggedEntityType.USER.label)
            .mapNotNull { event ->
                val login = event.body?.get("loginName")?.toString() ?: return@mapNotNull null
                uuidBody(event, "id") to login
            }.toMap()
}
