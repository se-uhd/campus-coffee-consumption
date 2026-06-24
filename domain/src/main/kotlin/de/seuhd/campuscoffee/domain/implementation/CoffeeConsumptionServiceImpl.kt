package de.seuhd.campuscoffee.domain.implementation

import de.seuhd.campuscoffee.domain.exceptions.ConflictException
import de.seuhd.campuscoffee.domain.exceptions.ForbiddenException
import de.seuhd.campuscoffee.domain.exceptions.ValidationException
import de.seuhd.campuscoffee.domain.model.CancellableIncrement
import de.seuhd.campuscoffee.domain.model.CoffeeConsumption
import de.seuhd.campuscoffee.domain.model.ConsumptionChange
import de.seuhd.campuscoffee.domain.model.Role
import de.seuhd.campuscoffee.domain.model.User
import de.seuhd.campuscoffee.domain.model.persistedId
import de.seuhd.campuscoffee.domain.ports.ChangeNoteContext
import de.seuhd.campuscoffee.domain.ports.api.CoffeeConsumptionService
import de.seuhd.campuscoffee.domain.ports.data.ActivityDataService
import de.seuhd.campuscoffee.domain.ports.data.CoffeeConsumptionDataService
import de.seuhd.campuscoffee.domain.ports.data.ConsumptionHistoryDataService
import de.seuhd.campuscoffee.domain.ports.data.CrudDataService
import de.seuhd.campuscoffee.domain.ports.data.UserDataService
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

/**
 * Domain implementation of [CoffeeConsumptionService]. Each `+1`/`-1` ([applyDelta]) and admin override
 * ([setTotal]) loads the member's consumption, applies the new count, and upserts it, recorded by the
 * event-sourced data adapter as a full-state event, reusing the same upsert path a review's approval
 * count used. Enforces the authorization rules: a member may view and step only their own count, an admin
 * may act on anyone, the absolute override is admin-only, a deactivated member is read-only, and the count
 * never goes negative.
 */
@Suppress("TooManyFunctions")
@Service
class CoffeeConsumptionServiceImpl(
    private val coffeeConsumptionDataService: CoffeeConsumptionDataService,
    private val consumptionHistoryDataService: ConsumptionHistoryDataService,
    private val activityDataService: ActivityDataService,
    private val userDataService: UserDataService,
    private val changeNoteContext: ChangeNoteContext,
    // the grace-period default lives once in application.yaml (campus-coffee.consumption.cancel-grace-period,
    // defaulting to 5m there); the domain binds the key directly because it cannot depend on the api module
    @param:Value($$"${campus-coffee.consumption.cancel-grace-period}") private val cancelGracePeriod: Duration
) : CrudServiceImpl<CoffeeConsumption, UUID>(CoffeeConsumption::class.java),
    CoffeeConsumptionService {
    override fun dataService(): CrudDataService<CoffeeConsumption, UUID> = coffeeConsumptionDataService

    override fun getByUserId(
        userId: UUID,
        actingUser: User
    ): CoffeeConsumption {
        requireMayView(userId, actingUser)
        return coffeeConsumptionDataService.getByUserId(userId)
    }

    @Transactional
    override fun applyDelta(
        userId: UUID,
        delta: Int,
        actingUser: User
    ): CoffeeConsumption {
        if (delta != 1 && delta != -1) {
            throw ValidationException("A single-step change must be +1 or -1.")
        }
        requireMaySelfMutate(userId, actingUser)
        val current = coffeeConsumptionDataService.getByUserId(userId)
        val newCount = current.count + delta
        if (newCount < 0) {
            // a valid -1 that conflicts with the current value (already zero): a 409, not a malformed request
            throw ConflictException("The coffee count cannot go below zero.")
        }
        return coffeeConsumptionDataService.upsert(current.copy(count = newCount))
    }

    @Transactional
    override fun setTotal(
        userId: UUID,
        total: Int,
        note: String?,
        actingUser: User
    ): CoffeeConsumption {
        requireAdmin(actingUser)
        if (total < 0) {
            throw ValidationException("The coffee count cannot be negative.")
        }
        val current = coffeeConsumptionDataService.getByUserId(userId)
        // the override is authoritative: it writes an absolute count asserted by the admin. It adds no
        // domain-level concurrency guard, but the write is not actually last-writer-wins: the event-sourced
        // read-model projection carries a @Version column, so a self-scan landing between this read and the
        // flush surfaces as a ConcurrentUpdateException (409), not a silent overwrite, and the admin re-applies.
        // expose the admin's note to the event store for the duration of this one recording upsert
        return changeNoteContext.runWithNote(note) {
            coffeeConsumptionDataService.upsert(current.copy(count = total))
        }
    }

    override fun recentChanges(
        userId: UUID,
        limit: Int,
        offset: Int,
        actingUser: User
    ): List<ConsumptionChange> {
        requireMayView(userId, actingUser)
        val consumption = coffeeConsumptionDataService.getByUserId(userId)
        return consumptionHistoryDataService.changes(consumption.persistedId, limit, offset)
    }

    @Transactional
    @Suppress("ThrowsCount") // each guard is a distinct, user-facing failure reason
    override fun cancel(
        userId: UUID,
        actingUser: User
    ): CoffeeConsumption {
        // a cancel must be recorded by the owner, so its event is attributed to the member and the activity
        // credits it at the original increment's price; an admin corrects a count with setTotal instead
        if (actingUser.persistedId != userId) {
            throw ForbiddenException("Only the owner may undo their own coffee; an admin adjusts the count instead.")
        }
        if (actingUser.active != true) {
            throw ForbiddenException("A deactivated member is read-only and cannot undo a coffee.")
        }
        // invariant: the grace/candidate gate here and the activity credit (which re-walks the member's
        // increments LIFO) derive the same chosen increment from the same stack rules, so they must stay
        // rule-identical. The count <= 0 recheck below plus the @Version-guarded write prevent a double-undo
        // even though this candidate read is not serialized with the write.
        val candidate =
            activityDataService.lastCancellableIncrement(userId, actingUser.loginName)
                ?: throw ConflictException("There is no recent coffee to undo.")
        if (candidate.createdAt.isBefore(graceCutoff())) {
            throw ConflictException(
                "The grace period to undo this coffee has passed; ask an admin to adjust your count."
            )
        }
        val current = coffeeConsumptionDataService.getByUserId(userId)
        if (current.count <= 0) {
            throw ConflictException("There is no coffee to undo.")
        }
        return coffeeConsumptionDataService.upsert(current.copy(count = current.count - 1))
    }

    override fun cancellableIncrement(
        userId: UUID,
        actingUser: User
    ): CancellableIncrement? {
        requireMayView(userId, actingUser)
        val ownerLogin = userDataService.getById(userId).loginName
        return cancellableWithinGrace(userId, ownerLogin)
    }

    @Transactional
    override fun createForUser(user: User): CoffeeConsumption =
        coffeeConsumptionDataService.upsert(CoffeeConsumption(user = user, count = 0))

    /** The member's most recent own increment if it is still within the grace period to undo, else null. */
    private fun cancellableWithinGrace(
        userId: UUID,
        ownerLogin: String
    ): CancellableIncrement? {
        val candidate = activityDataService.lastCancellableIncrement(userId, ownerLogin) ?: return null
        return if (candidate.createdAt.isBefore(graceCutoff())) null else candidate
    }

    /** The cutoff time before which a coffee can no longer be undone (now minus the grace period). */
    private fun graceCutoff(): LocalDateTime = LocalDateTime.now(ZoneId.of("UTC")).minus(cancelGracePeriod)

    /** Allows a read only when [actingUser] owns the consumption of [userId] or is an admin. */
    private fun requireMayView(
        userId: UUID,
        actingUser: User
    ) {
        if (actingUser.role != Role.ADMIN && actingUser.persistedId != userId) {
            throw ForbiddenException("A coffee count may be read only by its owner or an admin.")
        }
    }

    /**
     * Allows a `+1`/`-1` only when [actingUser] owns the consumption of [userId] or is an admin, and, for
     * the owner, only while the account is active (a deactivated member is authenticated read-only).
     */
    private fun requireMaySelfMutate(
        userId: UUID,
        actingUser: User
    ) {
        val isAdmin = actingUser.role == Role.ADMIN
        if (!isAdmin && actingUser.persistedId != userId) {
            throw ForbiddenException("A coffee count may be changed only by its owner or an admin.")
        }
        if (!isAdmin && actingUser.active != true) {
            throw ForbiddenException("A deactivated member is read-only and cannot change their coffee count.")
        }
    }

    /** Requires [actingUser] to be an admin (the absolute override is admin-only), else 403. */
    private fun requireAdmin(actingUser: User) {
        if (actingUser.role != Role.ADMIN) {
            throw ForbiddenException("Only an admin may override a coffee count.")
        }
    }
}
