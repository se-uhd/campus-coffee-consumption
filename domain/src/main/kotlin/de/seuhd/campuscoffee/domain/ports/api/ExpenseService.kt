package de.seuhd.campuscoffee.domain.ports.api

import de.seuhd.campuscoffee.domain.exceptions.ForbiddenException
import de.seuhd.campuscoffee.domain.exceptions.NotFoundException
import de.seuhd.campuscoffee.domain.exceptions.ValidationException
import de.seuhd.campuscoffee.domain.model.Expense
import de.seuhd.campuscoffee.domain.model.ExpenseType
import de.seuhd.campuscoffee.domain.model.User
import java.util.UUID

/**
 * Service interface for bean-purchase expenses. A port implemented by the domain and consumed by the API.
 *
 * A user records only their own purchases, always paid 100% from their own pocket (the split and the
 * buyer are server-derived, never taken from the request). Only an admin may record a purchase with a
 * kitty/private split or attributed to another user, and only an admin may correct or delete a purchase.
 * The two split portions must always sum to the total.
 */
interface ExpenseService {
    /**
     * Records an outlay the calling user made with their own money (100% private to themselves).
     *
     * @param expenseType whether this is a bean purchase ([ExpenseType.BEANS]) or another outlay ([ExpenseType.OTHER])
     * @param beanName    the bean name for a `BEANS` outlay (resolved or created); null for `OTHER`
     * @param weightGrams the bean weight in grams for a `BEANS` outlay; null for `OTHER`
     * @param amountCents the amount paid in euro cents (non-negative)
     * @param note        an optional free-text note
     * @param actingUser  the user recording their own outlay
     * @return the recorded expense
     * @throws ForbiddenException if [actingUser] is deactivated (read-only)
     * @throws ValidationException if a value is negative or the type/bean/weight combination is invalid
     */
    @Suppress("LongParameterList")
    fun recordOwn(
        expenseType: ExpenseType,
        beanName: String?,
        weightGrams: Int?,
        amountCents: Int,
        note: String?,
        actingUser: User
    ): Expense

    /**
     * Records an outlay on behalf of a user, with an explicit kitty/private split (admin-only).
     *
     * @param buyerUserId        the user credited with the private portion
     * @param expenseType        whether this is a bean purchase or another outlay
     * @param beanName           the bean name for a `BEANS` outlay (resolved or created); null for `OTHER`
     * @param weightGrams        the bean weight in grams for a `BEANS` outlay; null for `OTHER`
     * @param amountCents        the total amount paid in euro cents (non-negative)
     * @param privateAmountCents the portion paid from the buyer's pocket (credits the buyer)
     * @param kittyAmountCents   the portion paid from the kitty (draws the kitty down)
     * @param note               an optional free-text note
     * @param actingUser         the authenticated user attempting the record
     * @return the recorded expense
     * @throws ForbiddenException if [actingUser] is not an admin
     * @throws NotFoundException if no user exists for [buyerUserId]
     * @throws ValidationException if a value is negative, the split does not sum, or the type combination is invalid
     */
    @Suppress("LongParameterList")
    fun record(
        buyerUserId: UUID,
        expenseType: ExpenseType,
        beanName: String?,
        weightGrams: Int?,
        amountCents: Int,
        privateAmountCents: Int,
        kittyAmountCents: Int,
        note: String?,
        actingUser: User
    ): Expense

    /**
     * Corrects a recorded outlay (admin-only): replaces its type, bean, weight, amount, split, and note.
     *
     * @param expenseId          the id of the expense to correct
     * @param buyerUserId        the user credited with the private portion
     * @param expenseType        whether this is a bean purchase or another outlay
     * @param beanName           the bean name for a `BEANS` outlay (resolved or created); null for `OTHER`
     * @param weightGrams        the bean weight in grams for a `BEANS` outlay; null for `OTHER`
     * @param amountCents        the total amount paid in euro cents (non-negative)
     * @param privateAmountCents the portion paid from the buyer's pocket
     * @param kittyAmountCents   the portion paid from the kitty
     * @param note               an optional free-text note
     * @param actingUser         the authenticated user attempting the correction
     * @return the corrected expense
     * @throws ForbiddenException if [actingUser] is not an admin
     * @throws NotFoundException if no expense exists for [expenseId] or no user for [buyerUserId]
     * @throws ValidationException if a value is negative, the split does not sum, or the type combination is invalid
     */
    @Suppress("LongParameterList")
    fun update(
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
    ): Expense

    /**
     * Deletes a recorded purchase (admin-only).
     *
     * @param expenseId  the id of the expense to delete
     * @param actingUser the authenticated user attempting the deletion
     * @throws ForbiddenException if [actingUser] is not an admin
     * @throws NotFoundException if no expense exists for [expenseId]
     */
    fun delete(
        expenseId: UUID,
        actingUser: User
    )

    /**
     * Lists a user's recorded bean purchases (admin-only), so an admin can find one to correct or delete.
     *
     * @param userId     the buyer whose purchases to list
     * @param actingUser the authenticated user attempting the read
     * @return the buyer's expenses; never null, but may be empty
     * @throws ForbiddenException if [actingUser] is not an admin
     */
    fun listByBuyer(
        userId: UUID,
        actingUser: User
    ): List<Expense>

    /** Clears all expenses (dev/test reset only; destructive). */
    fun clear()
}
