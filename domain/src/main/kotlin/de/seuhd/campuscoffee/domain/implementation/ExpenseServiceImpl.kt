package de.seuhd.campuscoffee.domain.implementation

import de.seuhd.campuscoffee.domain.exceptions.ConflictException
import de.seuhd.campuscoffee.domain.exceptions.ForbiddenException
import de.seuhd.campuscoffee.domain.exceptions.ValidationException
import de.seuhd.campuscoffee.domain.model.Expense
import de.seuhd.campuscoffee.domain.model.Role
import de.seuhd.campuscoffee.domain.model.User
import de.seuhd.campuscoffee.domain.model.persistedId
import de.seuhd.campuscoffee.domain.ports.api.ExpenseService
import de.seuhd.campuscoffee.domain.ports.data.BalanceDataService
import de.seuhd.campuscoffee.domain.ports.data.BalanceLockService
import de.seuhd.campuscoffee.domain.ports.data.ExpenseDataService
import de.seuhd.campuscoffee.domain.ports.data.UserDataService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Domain implementation of [ExpenseService]. A user records only their own purchase, 100% private to
 * themselves. The buyer and split are derived here, never read from the request, so a user cannot
 * attribute a purchase to someone else or fund it from the kitty. Recording a split, attributing to another
 * user, correcting, and deleting are all admin-only. Every purchase is validated so the private and kitty
 * portions sum to the total, and an admin split whose kitty portion would overdraw the kitty is rejected (409).
 */
@Service
class ExpenseServiceImpl(
    private val expenseDataService: ExpenseDataService,
    private val userDataService: UserDataService,
    private val balanceDataService: BalanceDataService,
    private val balanceLock: BalanceLockService
) : ExpenseService {
    @Transactional
    override fun recordOwn(
        weightGrams: Int,
        amountCents: Int,
        note: String?,
        actingUser: User
    ): Expense {
        if (actingUser.active != true) {
            throw ForbiddenException("A deactivated user is read-only and cannot record a purchase.")
        }
        validateAmounts(weightGrams, amountCents, amountCents, 0)
        // a user's own purchase is always 100% from their own pocket
        return expenseDataService.upsert(
            Expense(
                buyer = actingUser,
                weightGrams = weightGrams,
                amountCents = amountCents,
                privateAmountCents = amountCents,
                kittyAmountCents = 0,
                note = note
            )
        )
    }

    @Transactional
    @Suppress("LongParameterList")
    override fun record(
        buyerUserId: UUID,
        weightGrams: Int,
        amountCents: Int,
        privateAmountCents: Int,
        kittyAmountCents: Int,
        note: String?,
        actingUser: User
    ): Expense {
        requireAdmin(actingUser)
        validateAmounts(weightGrams, amountCents, privateAmountCents, kittyAmountCents)
        balanceLock.lockKitty()
        if (kittyBalanceCents() - kittyAmountCents < 0) {
            throw ConflictException("This expense's kitty portion would make the kitty balance negative.")
        }
        val buyer = userDataService.getById(buyerUserId)
        return expenseDataService.upsert(
            Expense(
                buyer = buyer,
                weightGrams = weightGrams,
                amountCents = amountCents,
                privateAmountCents = privateAmountCents,
                kittyAmountCents = kittyAmountCents,
                note = note
            )
        )
    }

    @Transactional
    @Suppress("LongParameterList")
    override fun update(
        expenseId: UUID,
        buyerUserId: UUID,
        weightGrams: Int,
        amountCents: Int,
        privateAmountCents: Int,
        kittyAmountCents: Int,
        note: String?,
        actingUser: User
    ): Expense {
        requireAdmin(actingUser)
        validateAmounts(weightGrams, amountCents, privateAmountCents, kittyAmountCents)
        val existing = expenseDataService.getById(expenseId)
        // the buyer cannot be changed: the user activity keys on the buyer, so reassigning would leave the
        // old buyer credited and double-credit the new one. To move an expense, delete it and record a new one.
        if (buyerUserId != existing.buyer.persistedId) {
            throw ValidationException("An expense's buyer cannot be changed; delete it and record a new one.")
        }
        // correcting changes the kitty draw by (new − old); reject if that would overdraw the kitty
        balanceLock.lockKitty()
        if (kittyBalanceCents() + existing.kittyAmountCents - kittyAmountCents < 0) {
            throw ConflictException("This correction would make the kitty balance negative.")
        }
        return expenseDataService.upsert(
            existing.copy(
                weightGrams = weightGrams,
                amountCents = amountCents,
                privateAmountCents = privateAmountCents,
                kittyAmountCents = kittyAmountCents,
                note = note
            )
        )
    }

    override fun listByBuyer(
        userId: UUID,
        actingUser: User
    ): List<Expense> {
        requireAdmin(actingUser)
        return expenseDataService.getAllByBuyer(userId)
    }

    @Transactional
    override fun delete(
        expenseId: UUID,
        actingUser: User
    ) {
        requireAdmin(actingUser)
        // no explicit kitty lock here: a delete only shrinks the kitty draw (it cannot overdraw), so it has
        // no overdraw check to serialize. Its kitty recompute is still serialized against concurrent kitty
        // writers by the lock the projection takes around every recompute (see BalanceDataServiceImpl.maintain).
        expenseDataService.delete(expenseId)
    }

    override fun clear() = expenseDataService.clear()

    /** The current kitty balance in cents, read O(1) from the maintained projection (held under the lock). */
    private fun kittyBalanceCents(): Long = balanceDataService.kittyBalanceCents()

    /** Validates that nothing is negative and the private and kitty portions sum to the total. */
    private fun validateAmounts(
        weightGrams: Int,
        amountCents: Int,
        privateAmountCents: Int,
        kittyAmountCents: Int
    ) {
        if (listOf(weightGrams, amountCents, privateAmountCents, kittyAmountCents).any { it < 0 }) {
            throw ValidationException("Weight and amounts cannot be negative.")
        }
        if (privateAmountCents + kittyAmountCents != amountCents) {
            throw ValidationException("The private and kitty portions must sum to the total amount.")
        }
    }

    /** Requires [actingUser] to be an admin, else 403. */
    private fun requireAdmin(actingUser: User) {
        if (actingUser.role != Role.ADMIN) {
            throw ForbiddenException("Only an admin may record a split, attribute, correct, or delete a purchase.")
        }
    }
}
