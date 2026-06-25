package de.seuhd.campuscoffee.domain.implementation

import de.seuhd.campuscoffee.domain.exceptions.ConflictException
import de.seuhd.campuscoffee.domain.exceptions.ForbiddenException
import de.seuhd.campuscoffee.domain.exceptions.ValidationException
import de.seuhd.campuscoffee.domain.model.Expense
import de.seuhd.campuscoffee.domain.model.Role
import de.seuhd.campuscoffee.domain.model.User
import de.seuhd.campuscoffee.domain.ports.data.BalanceDataService
import de.seuhd.campuscoffee.domain.ports.data.BalanceLock
import de.seuhd.campuscoffee.domain.ports.data.ExpenseDataService
import de.seuhd.campuscoffee.domain.ports.data.UserDataService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.UUID

/**
 * Unit tests for ExpenseServiceImpl, mocking the data ports. The invariants under test: a member's own
 * purchase is always booked 100% private to themselves, a split or attribution by anyone but an admin is
 * forbidden, and the private and kitty portions must sum to the total with no negative values.
 */
class ExpenseServiceTest {
    private val expenseDataService: ExpenseDataService = mock()
    private val userDataService: UserDataService = mock()
    private val balanceDataService: BalanceDataService = mock()
    private val balanceLock: BalanceLock = mock()
    private val service = ExpenseServiceImpl(expenseDataService, userDataService, balanceDataService, balanceLock)

    private val memberId: UUID = UUID(0L, 1L)
    private val buyerId: UUID = UUID(0L, 2L)

    private val member =
        User(
            id = memberId,
            loginName = "max",
            emailAddress = "max@se.de",
            firstName = "Max",
            lastName = "M",
            role = Role.USER,
            active = true
        )
    private val admin =
        User(
            id = UUID(0L, 99L),
            loginName = "jane",
            emailAddress = "jane@se.de",
            firstName = "Jane",
            lastName = "D",
            role = Role.ADMIN,
            active = true
        )
    private val buyer = member.copy(id = buyerId, loginName = "buyer")

    @Test
    fun `recordOwn books the member's purchase fully private to themselves`() {
        whenever(expenseDataService.upsert(any())).thenAnswer { it.arguments[0] as Expense }

        val expense = service.recordOwn(weightGrams = 1000, amountCents = 900, note = "beans", actingUser = member)

        assertThat(expense.buyer).isEqualTo(member)
        assertThat(expense.privateAmountCents).isEqualTo(900)
        assertThat(expense.kittyAmountCents).isEqualTo(0)
    }

    @Test
    fun `recordOwn by a deactivated member throws ForbiddenException`() {
        assertThrows<ForbiddenException> {
            service.recordOwn(
                weightGrams = 1000,
                amountCents = 900,
                note = null,
                actingUser = member.copy(active = false)
            )
        }
        verify(expenseDataService, never()).upsert(any())
    }

    @Test
    fun `recordOwn with a negative amount throws ValidationException`() {
        assertThrows<ValidationException> {
            service.recordOwn(weightGrams = 1000, amountCents = -1, note = null, actingUser = member)
        }
        verify(expenseDataService, never()).upsert(any())
    }

    @Test
    fun `record by an admin with a matching split stores the kitty and private portions`() {
        whenever(balanceDataService.kittyBalanceCents()).thenReturn(10000L)
        whenever(userDataService.getById(buyerId)).thenReturn(buyer)
        whenever(expenseDataService.upsert(any())).thenAnswer { it.arguments[0] as Expense }

        val expense =
            service.record(
                buyerUserId = buyerId,
                weightGrams = 1000,
                amountCents = 900,
                privateAmountCents = 400,
                kittyAmountCents = 500,
                note = null,
                actingUser = admin
            )

        assertThat(expense.privateAmountCents).isEqualTo(400)
        assertThat(expense.kittyAmountCents).isEqualTo(500)
        assertThat(expense.buyer).isEqualTo(buyer)
    }

    @Test
    fun `record whose kitty portion exceeds the kitty balance throws ConflictException`() {
        whenever(balanceDataService.kittyBalanceCents()).thenReturn(300L)

        assertThrows<ConflictException> {
            service.record(
                buyerUserId = buyerId,
                weightGrams = 1000,
                amountCents = 900,
                privateAmountCents = 400,
                kittyAmountCents = 500,
                note = null,
                actingUser = admin
            )
        }
        verify(expenseDataService, never()).upsert(any())
    }

    @Test
    fun `record by a non-admin throws ForbiddenException`() {
        assertThrows<ForbiddenException> {
            service.record(
                buyerUserId = buyerId,
                weightGrams = 1000,
                amountCents = 900,
                privateAmountCents = 400,
                kittyAmountCents = 500,
                note = null,
                actingUser = member
            )
        }
        verify(expenseDataService, never()).upsert(any())
    }

    @Test
    fun `record with a split that does not sum to the total throws ValidationException`() {
        assertThrows<ValidationException> {
            service.record(
                buyerUserId = buyerId,
                weightGrams = 1000,
                amountCents = 900,
                privateAmountCents = 400,
                kittyAmountCents = 400,
                note = null,
                actingUser = admin
            )
        }
        verify(expenseDataService, never()).upsert(any())
    }

    @Test
    fun `record with a negative split portion throws ValidationException`() {
        assertThrows<ValidationException> {
            service.record(
                buyerUserId = buyerId,
                weightGrams = 1000,
                amountCents = 900,
                privateAmountCents = 1000,
                kittyAmountCents = -100,
                note = null,
                actingUser = admin
            )
        }
        verify(expenseDataService, never()).upsert(any())
    }

    @Test
    fun `delete by a non-admin throws ForbiddenException`() {
        assertThrows<ForbiddenException> { service.delete(UUID(0L, 5L), member) }
        verify(expenseDataService, never()).delete(any())
    }

    @Test
    fun `update with the same buyer succeeds and stores the corrected amounts`() {
        val expenseId = UUID(0L, 5L)
        val existing =
            Expense(
                id = expenseId,
                buyer = buyer,
                weightGrams = 1000,
                amountCents = 900,
                privateAmountCents = 400,
                kittyAmountCents = 500
            )
        whenever(expenseDataService.getById(expenseId)).thenReturn(existing)
        whenever(expenseDataService.upsert(any())).thenAnswer { it.arguments[0] as Expense }

        val updated =
            service.update(
                expenseId = expenseId,
                buyerUserId = buyerId,
                weightGrams = 1200,
                amountCents = 1000,
                privateAmountCents = 700,
                kittyAmountCents = 300,
                note = "corrected",
                actingUser = admin
            )

        assertThat(updated.buyer).isEqualTo(buyer)
        assertThat(updated.privateAmountCents).isEqualTo(700)
        assertThat(updated.kittyAmountCents).isEqualTo(300)
    }

    @Test
    fun `update that changes the buyer throws ValidationException`() {
        val expenseId = UUID(0L, 5L)
        val existing =
            Expense(
                id = expenseId,
                buyer = buyer,
                weightGrams = 1000,
                amountCents = 900,
                privateAmountCents = 400,
                kittyAmountCents = 500
            )
        whenever(expenseDataService.getById(expenseId)).thenReturn(existing)

        assertThrows<ValidationException> {
            service.update(
                expenseId = expenseId,
                // a different buyer than the existing expense's buyer
                buyerUserId = memberId,
                weightGrams = 1000,
                amountCents = 900,
                privateAmountCents = 400,
                kittyAmountCents = 500,
                note = null,
                actingUser = admin
            )
        }
        verify(expenseDataService, never()).upsert(any())
    }

    @Test
    fun `update that raises the kitty portion beyond the available balance throws ConflictException`() {
        val expenseId = UUID(0L, 5L)
        val existing =
            Expense(
                id = expenseId,
                buyer = buyer,
                weightGrams = 1000,
                amountCents = 900,
                privateAmountCents = 400,
                kittyAmountCents = 500
            )
        whenever(expenseDataService.getById(expenseId)).thenReturn(existing)
        // the kitty holds 300; raising this expense's kitty portion from 500 to 1000 draws 500 more than the
        // old portion, and 300 + 500 - 1000 = -200 < 0, so the differential overdraw guard refuses it
        whenever(balanceDataService.kittyBalanceCents()).thenReturn(300L)

        assertThrows<ConflictException> {
            service.update(
                expenseId = expenseId,
                buyerUserId = buyerId,
                weightGrams = 1000,
                amountCents = 1100,
                privateAmountCents = 100,
                kittyAmountCents = 1000,
                note = null,
                actingUser = admin
            )
        }
        verify(expenseDataService, never()).upsert(any())
    }

    @Test
    fun `record acquires the kitty lock before reading the kitty balance`() {
        whenever(balanceDataService.kittyBalanceCents()).thenReturn(10000L)
        whenever(userDataService.getById(buyerId)).thenReturn(buyer)
        whenever(expenseDataService.upsert(any())).thenAnswer { it.arguments[0] as Expense }

        service.record(
            buyerUserId = buyerId,
            weightGrams = 1000,
            amountCents = 900,
            privateAmountCents = 400,
            kittyAmountCents = 500,
            note = null,
            actingUser = admin
        )

        // the overdraw guard is only sound if the lock is taken before the balance is read
        val ordered = inOrder(balanceLock, balanceDataService)
        ordered.verify(balanceLock).lockKitty()
        ordered.verify(balanceDataService).kittyBalanceCents()
    }

    @Test
    fun `update acquires the kitty lock before reading the kitty balance`() {
        val expenseId = UUID(0L, 5L)
        val existing =
            Expense(
                id = expenseId,
                buyer = buyer,
                weightGrams = 1000,
                amountCents = 900,
                privateAmountCents = 400,
                kittyAmountCents = 500
            )
        whenever(expenseDataService.getById(expenseId)).thenReturn(existing)
        whenever(balanceDataService.kittyBalanceCents()).thenReturn(10000L)
        whenever(expenseDataService.upsert(any())).thenAnswer { it.arguments[0] as Expense }

        service.update(
            expenseId = expenseId,
            buyerUserId = buyerId,
            weightGrams = 1000,
            amountCents = 900,
            privateAmountCents = 400,
            kittyAmountCents = 500,
            note = null,
            actingUser = admin
        )

        val ordered = inOrder(balanceLock, balanceDataService)
        ordered.verify(balanceLock).lockKitty()
        ordered.verify(balanceDataService).kittyBalanceCents()
    }

    @Test
    fun `listByBuyer by a non-admin throws ForbiddenException`() {
        assertThrows<ForbiddenException> { service.listByBuyer(buyerId, member) }
        verify(expenseDataService, never()).getAllByBuyer(any())
    }

    @Test
    fun `listByBuyer returns the buyer's expenses for an admin`() {
        val expense =
            Expense(
                id = UUID(0L, 5L),
                buyer = buyer,
                weightGrams = 1000,
                amountCents = 900,
                privateAmountCents = 900,
                kittyAmountCents = 0
            )
        whenever(expenseDataService.getAllByBuyer(buyerId)).thenReturn(listOf(expense))

        val expenses = service.listByBuyer(buyerId, admin)

        assertThat(expenses).containsExactly(expense)
    }
}
