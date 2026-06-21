package de.seuhd.campuscoffee.domain.implementation

import de.seuhd.campuscoffee.domain.exceptions.ForbiddenException
import de.seuhd.campuscoffee.domain.model.CancellableIncrement
import de.seuhd.campuscoffee.domain.model.CoffeeConsumption
import de.seuhd.campuscoffee.domain.model.CoffeePrice
import de.seuhd.campuscoffee.domain.model.LedgerEntry
import de.seuhd.campuscoffee.domain.model.LedgerEntryType
import de.seuhd.campuscoffee.domain.model.Role
import de.seuhd.campuscoffee.domain.model.User
import de.seuhd.campuscoffee.domain.ports.api.CoffeeConsumptionService
import de.seuhd.campuscoffee.domain.ports.api.CoffeePriceService
import de.seuhd.campuscoffee.domain.ports.data.CoffeeConsumptionDataService
import de.seuhd.campuscoffee.domain.ports.data.LedgerDataService
import de.seuhd.campuscoffee.domain.ports.data.UserDataService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.LocalDateTime
import java.util.UUID

/**
 * Unit tests for AccountingServiceImpl, mocking the data ports and the price/consumption services. Covers
 * the read-side composition (summary, member ledger, kitty balance/ledger, overview) and the authorization
 * rules: a member reads only their own balance and may not read the kitty ledger or the overview.
 */
class AccountingServiceTest {
    private val ledgerDataService: LedgerDataService = mock()
    private val coffeePriceService: CoffeePriceService = mock()
    private val coffeeConsumptionDataService: CoffeeConsumptionDataService = mock()
    private val coffeeConsumptionService: CoffeeConsumptionService = mock()
    private val userDataService: UserDataService = mock()

    private val service =
        AccountingServiceImpl(
            ledgerDataService,
            coffeePriceService,
            coffeeConsumptionDataService,
            coffeeConsumptionService,
            userDataService
        )

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

    private fun entry(
        type: LedgerEntryType,
        amountCents: Long,
        runningBalanceCents: Long
    ) = LedgerEntry(
        type = type,
        seq = 1L,
        createdAt = LocalDateTime.now(),
        createdBy = "max",
        note = null,
        amountCents = amountCents,
        runningBalanceCents = runningBalanceCents
    )

    @Test
    fun `memberSummary composes the count, price, balance, kitty, and ledger for the owner`() {
        val ledger = listOf(entry(LedgerEntryType.CONSUMPTION, -50, -50))
        whenever(userDataService.getById(memberId)).thenReturn(member)
        whenever(ledgerDataService.memberLedger(memberId, "max")).thenReturn(ledger)
        whenever(
            coffeeConsumptionDataService.getByUserId(memberId)
        ).thenReturn(CoffeeConsumption(user = member, count = 1))
        whenever(coffeePriceService.getCurrent()).thenReturn(CoffeePrice(amountCents = 50))
        whenever(ledgerDataService.kittyLedger()).thenReturn(emptyList())
        whenever(coffeeConsumptionService.cancellableIncrement(memberId, member))
            .thenReturn(CancellableIncrement(1L, LocalDateTime.now(), 50))

        val summary = service.memberSummary(memberId, 10, 0, member)

        assertThat(summary.count).isEqualTo(1)
        assertThat(summary.priceCents).isEqualTo(50)
        assertThat(summary.balanceCents).isEqualTo(-50)
        assertThat(summary.kittyBalanceCents).isEqualTo(0)
        assertThat(summary.cancellable).isTrue()
        assertThat(summary.ledger).hasSize(1)
    }

    @Test
    fun `memberSummary reports cancellable false when the count is zero`() {
        // even if a stale increment is still within the grace period, a zero count is not cancellable
        whenever(userDataService.getById(memberId)).thenReturn(member)
        whenever(ledgerDataService.memberLedger(memberId, "max")).thenReturn(emptyList())
        whenever(
            coffeeConsumptionDataService.getByUserId(memberId)
        ).thenReturn(CoffeeConsumption(user = member, count = 0))
        whenever(coffeePriceService.getCurrent()).thenReturn(CoffeePrice(amountCents = 50))
        whenever(ledgerDataService.kittyLedger()).thenReturn(emptyList())

        val summary = service.memberSummary(memberId, 10, 0, member)

        assertThat(summary.count).isEqualTo(0)
        assertThat(summary.cancellable).isFalse()
        // the count gate short-circuits, so the cancellable lookup is never even consulted
        verify(coffeeConsumptionService, never()).cancellableIncrement(any(), any())
    }

    @Test
    fun `memberSummary by a non-owner non-admin throws ForbiddenException`() {
        val stranger = member.copy(id = UUID(0L, 7L), loginName = "other")
        whenever(userDataService.getById(memberId)).thenReturn(member)

        assertThrows<ForbiddenException> { service.memberSummary(memberId, 10, 0, stranger) }
    }

    @Test
    fun `memberLedger returns the newest-first page for an admin`() {
        whenever(userDataService.getById(memberId)).thenReturn(member)
        whenever(ledgerDataService.memberLedger(memberId, "max"))
            .thenReturn(
                listOf(entry(LedgerEntryType.CONSUMPTION, -50, -50), entry(LedgerEntryType.SETTLEMENT, 100, 50))
            )

        val page = service.memberLedger(memberId, 10, 0, admin)

        // newest first: the settlement, then the consumption
        assertThat(page.map { it.type }).containsExactly(LedgerEntryType.SETTLEMENT, LedgerEntryType.CONSUMPTION)
    }

    @Test
    fun `kittyBalanceCents returns the last running balance of the kitty ledger`() {
        whenever(ledgerDataService.kittyLedger())
            .thenReturn(
                listOf(
                    entry(LedgerEntryType.KITTY_ADJUSTMENT, 500, 500),
                    entry(LedgerEntryType.KITTY_EXPENSE, -200, 300)
                )
            )

        assertThat(service.kittyBalanceCents()).isEqualTo(300)
    }

    @Test
    fun `kittyLedger by a non-admin throws ForbiddenException`() {
        assertThrows<ForbiddenException> { service.kittyLedger(10, 0, member) }
    }

    @Test
    fun `allBalances by a non-admin throws ForbiddenException`() {
        assertThrows<ForbiddenException> { service.allBalances(member) }
    }

    @Test
    fun `allBalances returns each member's count and balance for an admin`() {
        whenever(userDataService.getAll()).thenReturn(listOf(member))
        whenever(ledgerDataService.memberLedger(memberId, "max"))
            .thenReturn(listOf(entry(LedgerEntryType.CONSUMPTION, -50, -50)))
        whenever(
            coffeeConsumptionDataService.getByUserId(memberId)
        ).thenReturn(CoffeeConsumption(user = member, count = 1))

        val balances = service.allBalances(admin)

        assertThat(balances).singleElement()
        assertThat(balances.first().count).isEqualTo(1)
        assertThat(balances.first().balanceCents).isEqualTo(-50)
    }

    @Test
    fun `allBalances reports a zero balance for a member with no ledger entries`() {
        whenever(userDataService.getAll()).thenReturn(listOf(member))
        whenever(ledgerDataService.memberLedger(any(), any())).thenReturn(emptyList())
        whenever(
            coffeeConsumptionDataService.getByUserId(memberId)
        ).thenReturn(CoffeeConsumption(user = member, count = 0))

        assertThat(service.allBalances(admin).first().balanceCents).isEqualTo(0)
    }
}
