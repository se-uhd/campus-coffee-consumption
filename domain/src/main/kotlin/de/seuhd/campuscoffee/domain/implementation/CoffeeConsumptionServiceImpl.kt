package de.seuhd.campuscoffee.domain.implementation

import de.seuhd.campuscoffee.domain.exceptions.ConflictException
import de.seuhd.campuscoffee.domain.exceptions.ForbiddenException
import de.seuhd.campuscoffee.domain.exceptions.ValidationException
import de.seuhd.campuscoffee.domain.model.objects.CoffeeConsumption
import de.seuhd.campuscoffee.domain.model.objects.ConsumptionChange
import de.seuhd.campuscoffee.domain.model.objects.Role
import de.seuhd.campuscoffee.domain.model.objects.User
import de.seuhd.campuscoffee.domain.model.objects.persistedId
import de.seuhd.campuscoffee.domain.ports.ChangeNoteContext
import de.seuhd.campuscoffee.domain.ports.api.CoffeeConsumptionService
import de.seuhd.campuscoffee.domain.ports.data.CoffeeConsumptionDataService
import de.seuhd.campuscoffee.domain.ports.data.ConsumptionHistoryDataService
import de.seuhd.campuscoffee.domain.ports.data.CrudDataService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Domain implementation of [CoffeeConsumptionService]. Each `+1`/`-1` ([applyDelta]) and admin override
 * ([setTotal]) loads the member's consumption, applies the new count, and upserts it — recorded by the
 * event-sourced data adapter as a full-state event, reusing the same upsert path a review's approval
 * count used. Enforces the authorization rules: a member may view and step only their own count, an admin
 * may act on anyone, the absolute override is admin-only, a deactivated member is read-only, and the count
 * never goes negative.
 */
@Service
class CoffeeConsumptionServiceImpl(
    private val coffeeConsumptionDataService: CoffeeConsumptionDataService,
    private val consumptionHistoryDataService: ConsumptionHistoryDataService,
    private val changeNoteContext: ChangeNoteContext
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
    override fun createForUser(user: User): CoffeeConsumption =
        coffeeConsumptionDataService.upsert(CoffeeConsumption(user = user, count = 0))

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
     * Allows a `+1`/`-1` only when [actingUser] owns the consumption of [userId] or is an admin, and — for
     * the owner — only while the account is active (a deactivated member is authenticated read-only).
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
