package de.seuhd.campuscoffee.data.persistence.eventsourcing

import de.seuhd.campuscoffee.data.persistence.entities.KittyBalanceEntity
import de.seuhd.campuscoffee.data.persistence.entities.MemberBalanceEntity
import de.seuhd.campuscoffee.data.persistence.repositories.KittyBalanceRepository
import de.seuhd.campuscoffee.data.persistence.repositories.MemberBalanceRepository
import de.seuhd.campuscoffee.data.persistence.repositories.UserRepository
import de.seuhd.campuscoffee.domain.ports.data.ActivityDataService
import de.seuhd.campuscoffee.domain.ports.data.BalanceDataService
import de.seuhd.campuscoffee.domain.ports.data.BalanceLock
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Maintains and serves the balance projections (per-member balance and the single kitty balance). It is the
 * one place that keeps the materialized balances consistent with the event log: a write calls [maintain] in
 * the same transaction (so a rollback reverts the balance too), and a full read-model rebuild calls
 * [rebuildAll].
 *
 * The maintained value is always the exact result of the existing event-log walk
 * ([ActivityDataService.userActivity] / [ActivityDataService.kittyHistory]), recomputed for the affected
 * member (and the kitty) after the triggering event, so the projection cannot drift from the authoritative
 * walk. The reads then serve the hot paths (the per-member overview and the kitty-overdraw guard) in one
 * indexed lookup instead of replaying a whole stream.
 *
 * @property memberBalanceRepository storage for the per-member balance rows
 * @property kittyBalanceRepository storage for the single kitty-balance row
 * @property activityDataService the authoritative event-log walk reused to recompute a balance
 * @property userRepository resolves a member's login (the walk needs it) and enumerates members for a rebuild
 * @property balanceLock serializes the per-write recomputes so a concurrent write cannot lost-update a row
 */
@Service
class BalanceProjection(
    private val memberBalanceRepository: MemberBalanceRepository,
    private val kittyBalanceRepository: KittyBalanceRepository,
    private val activityDataService: ActivityDataService,
    private val userRepository: UserRepository,
    private val balanceLock: BalanceLock
) : BalanceDataService {
    override fun memberBalanceCents(userId: UUID): Long =
        memberBalanceRepository.findByIdOrNull(userId)?.balanceCents ?: 0L

    override fun allMemberBalancesCents(): Map<UUID, Long> =
        memberBalanceRepository.findAll().associate { requireNotNull(it.userId) to it.balanceCents }

    override fun kittyBalanceCents(): Long =
        kittyBalanceRepository.findByIdOrNull(KittyBalanceEntity.SINGLETON_ID)?.balanceCents ?: 0L

    /**
     * Refreshes the balances the given event affects, recomputing from the log: a consumption refreshes its
     * member, an expense its buyer plus the kitty, a payment its member (if any) plus the kitty; a user or
     * price event affects no balance. Called within the write transaction, after the event is appended and
     * projected, so the recompute sees the new event.
     *
     * It serializes every recompute through [balanceLock]: the kitty lock around a kitty recompute and the
     * per-member lock around a member recompute, so a concurrent write (a deposit, a delete, a self-scan
     * racing an admin step) cannot lost-update the unversioned projection row. The locks are always acquired
     * **kitty before member**, the same global order a kitty-mutating service uses (it holds the kitty lock
     * for its overdraw check before this runs), so the paths cannot deadlock. Centralizing the locks here
     * means no write path can recompute a projection without holding the right lock.
     *
     * @param event the just-appended event whose affected balances to refresh
     */
    fun maintain(event: EventEntity) {
        val type = LoggedEntityType.ofLabel(requireNotNull(event.entityType))
        val memberId =
            when (type) {
                LoggedEntityType.COFFEE_CONSUMPTION -> bodyUserId(event, "userId")
                LoggedEntityType.EXPENSE -> bodyUserId(event, "buyerUserId")
                LoggedEntityType.PAYMENT -> bodyUserId(event, "userId")
                // a user or price write moves no balance (a price change is valued as-of, never retroactively)
                LoggedEntityType.USER, LoggedEntityType.COFFEE_PRICE -> null
            }
        val touchesKitty = type == LoggedEntityType.EXPENSE || type == LoggedEntityType.PAYMENT
        // acquire in the fixed global order (kitty before member) so concurrent writes cannot deadlock
        if (touchesKitty) {
            balanceLock.lockKitty()
        }
        if (memberId != null) {
            balanceLock.lockMember(memberId)
        }
        if (touchesKitty) {
            recomputeKitty()
        }
        if (memberId != null) {
            recomputeMember(memberId)
        }
    }

    /** Empties both balance projections (a member row also cascades away when its member is deleted). */
    fun clear() {
        memberBalanceRepository.deleteAllInBatch()
        kittyBalanceRepository.deleteAllInBatch()
    }

    /** Rebuilds every balance from the log: clears the projections, then recomputes each member and the kitty. */
    fun rebuildAll() {
        clear()
        userRepository.findAll().forEach { recomputeMember(requireNotNull(it.id)) }
        recomputeKitty()
    }

    /** Recomputes and stores one member's balance from the event-log walk (a no-op if the member is gone). */
    private fun recomputeMember(userId: UUID) {
        val login = userRepository.findByIdOrNull(userId)?.loginName ?: return
        val balance = activityDataService.userActivity(userId, login).lastOrNull()?.runningBalanceCents ?: 0L
        memberBalanceRepository.save(
            MemberBalanceEntity().apply {
                this.userId = userId
                balanceCents = balance
            }
        )
    }

    /** Recomputes and stores the single kitty balance from the kitty walk over the global money stream. */
    private fun recomputeKitty() {
        val balance = activityDataService.kittyHistory().lastOrNull()?.runningBalanceCents ?: 0L
        kittyBalanceRepository.save(KittyBalanceEntity().apply { balanceCents = balance })
    }

    /** Reads an owner-id field (a string UUID) from an event body, or null when the body carries none. */
    private fun bodyUserId(
        event: EventEntity,
        key: String
    ): UUID? = event.body?.get(key)?.let { UUID.fromString(it.toString()) }
}
