package de.seuhd.campuscoffee.domain.implementation

import de.seuhd.campuscoffee.domain.exceptions.ConflictException
import de.seuhd.campuscoffee.domain.exceptions.ForbiddenException
import de.seuhd.campuscoffee.domain.exceptions.ValidationException
import de.seuhd.campuscoffee.domain.model.Payment
import de.seuhd.campuscoffee.domain.model.Role
import de.seuhd.campuscoffee.domain.model.User
import de.seuhd.campuscoffee.domain.ports.data.BalanceDataService
import de.seuhd.campuscoffee.domain.ports.data.BalanceLockService
import de.seuhd.campuscoffee.domain.ports.data.PaymentDataService
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
 * Unit tests for PaymentServiceImpl, mocking the data ports. The invariants under test: every operation is
 * admin-only, a deposit amount must be positive (and carries the user), and a kitty adjustment must be
 * non-zero (and carries no user).
 */
class PaymentServiceTest {
    private val paymentDataService: PaymentDataService = mock()
    private val userDataService: UserDataService = mock()
    private val balanceDataService: BalanceDataService = mock()
    private val balanceLock: BalanceLockService = mock()
    private val service = PaymentServiceImpl(paymentDataService, userDataService, balanceDataService, balanceLock)

    private val memberId: UUID = UUID(0L, 1L)

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

    @Test
    fun `recordDeposit by an admin stores a positive deposit carrying the member`() {
        whenever(userDataService.getById(memberId)).thenReturn(member)
        whenever(paymentDataService.upsert(any())).thenAnswer { it.arguments[0] as Payment }

        val payment = service.recordDeposit(memberId, 1000, "cash", admin)

        assertThat(payment.amountCents).isEqualTo(1000)
        assertThat(payment.user).isEqualTo(member)
    }

    @Test
    fun `recordDeposit by a non-admin throws ForbiddenException`() {
        assertThrows<ForbiddenException> { service.recordDeposit(memberId, 1000, null, member) }
        verify(paymentDataService, never()).upsert(any())
    }

    @Test
    fun `recordDeposit of a non-positive amount throws ValidationException`() {
        assertThrows<ValidationException> { service.recordDeposit(memberId, 0, null, admin) }
        verify(paymentDataService, never()).upsert(any())
    }

    @Test
    fun `adjustKitty by an admin stores a signed adjustment carrying no member`() {
        whenever(balanceDataService.kittyBalanceCents()).thenReturn(1000L)
        whenever(paymentDataService.upsert(any())).thenAnswer { it.arguments[0] as Payment }

        val payment = service.adjustKitty(-250, "correction", admin)

        assertThat(payment.amountCents).isEqualTo(-250)
        assertThat(payment.user).isNull()
    }

    @Test
    fun `adjustKitty that would overdraw the kitty throws ConflictException`() {
        whenever(balanceDataService.kittyBalanceCents()).thenReturn(100L)

        assertThrows<ConflictException> { service.adjustKitty(-250, "overdraw", admin) }
        verify(paymentDataService, never()).upsert(any())
    }

    @Test
    fun `adjustKitty by a non-admin throws ForbiddenException`() {
        assertThrows<ForbiddenException> { service.adjustKitty(500, null, member) }
        verify(paymentDataService, never()).upsert(any())
    }

    @Test
    fun `adjustKitty of zero throws ValidationException`() {
        assertThrows<ValidationException> { service.adjustKitty(0, null, admin) }
        verify(paymentDataService, never()).upsert(any())
    }

    @Test
    fun `adjustKitty acquires the kitty lock before reading the kitty balance`() {
        whenever(balanceDataService.kittyBalanceCents()).thenReturn(1000L)
        whenever(paymentDataService.upsert(any())).thenAnswer { it.arguments[0] as Payment }

        service.adjustKitty(-250, "correction", admin)

        // the overdraw guard is only sound if the lock is taken before the balance is read; a refactor that
        // reordered or dropped the lock would keep every other test green but reintroduce the TOCTOU race
        val ordered = inOrder(balanceLock, balanceDataService)
        ordered.verify(balanceLock).lockKitty()
        ordered.verify(balanceDataService).kittyBalanceCents()
    }
}
