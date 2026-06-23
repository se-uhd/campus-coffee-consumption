package de.seuhd.campuscoffee.domain.implementation

import de.seuhd.campuscoffee.domain.exceptions.ConflictException
import de.seuhd.campuscoffee.domain.exceptions.ForbiddenException
import de.seuhd.campuscoffee.domain.exceptions.ValidationException
import de.seuhd.campuscoffee.domain.model.LedgerEntry
import de.seuhd.campuscoffee.domain.model.LedgerEntryType
import de.seuhd.campuscoffee.domain.model.Payment
import de.seuhd.campuscoffee.domain.model.Role
import de.seuhd.campuscoffee.domain.model.User
import de.seuhd.campuscoffee.domain.ports.data.KittyLock
import de.seuhd.campuscoffee.domain.ports.data.LedgerDataService
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
import java.time.LocalDateTime
import java.util.UUID

/**
 * Unit tests for PaymentServiceImpl, mocking the data ports. The invariants under test: every operation is
 * admin-only, a settlement amount must be positive (and carries the member), and a kitty adjustment must be
 * non-zero (and carries no member).
 */
class PaymentServiceTest {
    private val paymentDataService: PaymentDataService = mock()
    private val userDataService: UserDataService = mock()
    private val ledgerDataService: LedgerDataService = mock()
    private val kittyLock: KittyLock = mock()
    private val service = PaymentServiceImpl(paymentDataService, userDataService, ledgerDataService, kittyLock)

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
    fun `recordSettlement by an admin stores a positive settlement carrying the member`() {
        whenever(userDataService.getById(memberId)).thenReturn(member)
        whenever(paymentDataService.upsert(any())).thenAnswer { it.arguments[0] as Payment }

        val payment = service.recordSettlement(memberId, 1000, "cash", admin)

        assertThat(payment.amountCents).isEqualTo(1000)
        assertThat(payment.user).isEqualTo(member)
    }

    @Test
    fun `recordSettlement by a non-admin throws ForbiddenException`() {
        assertThrows<ForbiddenException> { service.recordSettlement(memberId, 1000, null, member) }
        verify(paymentDataService, never()).upsert(any())
    }

    @Test
    fun `recordSettlement of a non-positive amount throws ValidationException`() {
        assertThrows<ValidationException> { service.recordSettlement(memberId, 0, null, admin) }
        verify(paymentDataService, never()).upsert(any())
    }

    @Test
    fun `adjustKitty by an admin stores a signed adjustment carrying no member`() {
        whenever(ledgerDataService.kittyLedger()).thenReturn(kittyWith(1000))
        whenever(paymentDataService.upsert(any())).thenAnswer { it.arguments[0] as Payment }

        val payment = service.adjustKitty(-250, "correction", admin)

        assertThat(payment.amountCents).isEqualTo(-250)
        assertThat(payment.user).isNull()
    }

    @Test
    fun `adjustKitty that would overdraw the kitty throws ConflictException`() {
        whenever(ledgerDataService.kittyLedger()).thenReturn(kittyWith(100))

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
        whenever(ledgerDataService.kittyLedger()).thenReturn(kittyWith(1000))
        whenever(paymentDataService.upsert(any())).thenAnswer { it.arguments[0] as Payment }

        service.adjustKitty(-250, "correction", admin)

        // the overdraw guard is only sound if the lock is taken before the balance is read; a refactor that
        // reordered or dropped the lock would keep every other test green but reintroduce the TOCTOU race
        val ordered = inOrder(kittyLock, ledgerDataService)
        ordered.verify(kittyLock).lockForUpdate()
        ordered.verify(ledgerDataService).kittyLedger()
    }

    private fun kittyWith(balanceCents: Long): List<LedgerEntry> =
        listOf(
            LedgerEntry(
                type = LedgerEntryType.KITTY_ADJUSTMENT,
                seq = 1L,
                createdAt = LocalDateTime.of(2026, 1, 1, 0, 0),
                createdBy = "system",
                note = null,
                amountCents = balanceCents,
                runningBalanceCents = balanceCents
            )
        )
}
