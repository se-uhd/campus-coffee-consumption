package de.seuhd.campuscoffee.domain.implementation

import de.seuhd.campuscoffee.domain.exceptions.ConflictException
import de.seuhd.campuscoffee.domain.exceptions.ForbiddenException
import de.seuhd.campuscoffee.domain.exceptions.ValidationException
import de.seuhd.campuscoffee.domain.model.CoffeeBean
import de.seuhd.campuscoffee.domain.model.Expense
import de.seuhd.campuscoffee.domain.model.ExpenseType
import de.seuhd.campuscoffee.domain.model.Role
import de.seuhd.campuscoffee.domain.model.User
import de.seuhd.campuscoffee.domain.model.persistedId
import de.seuhd.campuscoffee.domain.ports.api.CoffeeBeanService
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
    private val coffeeBeanService: CoffeeBeanService,
    private val balanceDataService: BalanceDataService,
    private val balanceLock: BalanceLockService
) : ExpenseService {
    @Transactional
    @Suppress("LongParameterList")
    override fun recordOwn(
        expenseType: ExpenseType,
        beanName: String?,
        weightGrams: Int?,
        amountCents: Int,
        note: String?,
        actingUser: User
    ): Expense {
        if (actingUser.active != true) {
            throw ForbiddenException("A deactivated user is read-only and cannot record a purchase.")
        }
        validateType(expenseType, beanName, weightGrams)
        validateAmounts(amountCents, amountCents, 0)
        // a user's own purchase is always 100% from their own pocket
        return expenseDataService.upsert(
            Expense(
                buyer = actingUser,
                expenseType = expenseType,
                bean = resolveBean(expenseType, beanName),
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
        expenseType: ExpenseType,
        beanName: String?,
        weightGrams: Int?,
        amountCents: Int,
        privateAmountCents: Int,
        kittyAmountCents: Int,
        note: String?,
        actingUser: User
    ): Expense {
        requireAdmin(actingUser)
        validateType(expenseType, beanName, weightGrams)
        validateAmounts(amountCents, privateAmountCents, kittyAmountCents)
        balanceLock.lockKitty()
        if (kittyBalanceCents() - kittyAmountCents < 0) {
            throw ConflictException("This expense's kitty portion would make the kitty balance negative.")
        }
        val buyer = userDataService.getById(buyerUserId)
        return expenseDataService.upsert(
            Expense(
                buyer = buyer,
                expenseType = expenseType,
                bean = resolveBean(expenseType, beanName),
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
        expenseType: ExpenseType,
        beanName: String?,
        weightGrams: Int?,
        amountCents: Int,
        privateAmountCents: Int,
        kittyAmountCents: Int,
        note: String?,
        actingUser: User
    ): Expense {
        requireAdmin(actingUser)
        validateType(expenseType, beanName, weightGrams)
        validateAmounts(amountCents, privateAmountCents, kittyAmountCents)
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
                expenseType = expenseType,
                bean = resolveBean(expenseType, beanName),
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

    /** Validates that no amount is negative and the private and kitty portions sum to the total. */
    private fun validateAmounts(
        amountCents: Int,
        privateAmountCents: Int,
        kittyAmountCents: Int
    ) {
        if (listOf(amountCents, privateAmountCents, kittyAmountCents).any { it < 0 }) {
            throw ValidationException("Amounts cannot be negative.")
        }
        if (privateAmountCents + kittyAmountCents != amountCents) {
            throw ValidationException("The private and kitty portions must sum to the total amount.")
        }
    }

    /**
     * Validates the type combination: a `BEANS` outlay must carry a non-blank bean name and a positive
     * weight, while an `OTHER` outlay must carry neither.
     */
    @Suppress("ThrowsCount") // each branch is a distinct, user-facing validation message
    private fun validateType(
        expenseType: ExpenseType,
        beanName: String?,
        weightGrams: Int?
    ) {
        when (expenseType) {
            ExpenseType.BEANS -> {
                if (beanName.isNullOrBlank()) {
                    throw ValidationException("A bean purchase must name the beans.")
                }
                if (weightGrams == null || weightGrams <= 0) {
                    throw ValidationException("A bean purchase must have a positive weight.")
                }
            }
            ExpenseType.OTHER -> {
                if (!beanName.isNullOrBlank()) {
                    throw ValidationException("A non-bean outlay must not name a bean.")
                }
                if (weightGrams != null) {
                    throw ValidationException("A non-bean outlay must not have a weight.")
                }
            }
        }
    }

    /** Resolves (or creates) the bean for a `BEANS` outlay; an `OTHER` outlay has no bean. */
    private fun resolveBean(
        expenseType: ExpenseType,
        beanName: String?
    ): CoffeeBean? =
        when (expenseType) {
            ExpenseType.BEANS -> coffeeBeanService.resolveOrCreate(requireNotNull(beanName))
            ExpenseType.OTHER -> null
        }

    /** Requires [actingUser] to be an admin, else 403. */
    private fun requireAdmin(actingUser: User) {
        if (actingUser.role != Role.ADMIN) {
            throw ForbiddenException("Only an admin may record a split, attribute, correct, or delete a purchase.")
        }
    }
}
