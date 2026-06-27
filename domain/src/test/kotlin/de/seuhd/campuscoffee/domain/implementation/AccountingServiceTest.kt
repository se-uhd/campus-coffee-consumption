package de.seuhd.campuscoffee.domain.implementation

import de.seuhd.campuscoffee.domain.exceptions.ForbiddenException
import de.seuhd.campuscoffee.domain.model.ActivityEntry
import de.seuhd.campuscoffee.domain.model.ActivityEntryType
import de.seuhd.campuscoffee.domain.model.CancellableIncrement
import de.seuhd.campuscoffee.domain.model.CoffeeConsumption
import de.seuhd.campuscoffee.domain.model.CoffeePrice
import de.seuhd.campuscoffee.domain.model.Role
import de.seuhd.campuscoffee.domain.model.User
import de.seuhd.campuscoffee.domain.ports.api.CoffeeConsumptionService
import de.seuhd.campuscoffee.domain.ports.api.CoffeePriceService
import de.seuhd.campuscoffee.domain.ports.data.ActivityDataService
import de.seuhd.campuscoffee.domain.ports.data.BalanceDataService
import de.seuhd.campuscoffee.domain.ports.data.CoffeeConsumptionDataService
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
 * Unit tests for AccountingServiceImpl, mocking the data ports and the price/consumption services. Covers the
 * read-side money numbers (the landing summary, the kitty balance, the all-user overview) and the
 * authorization rules: a user reads only their own summary and may not read the overview. The chronological
 * feeds moved to [ActivityServiceImpl] (see [ActivityServiceTest]).
 */
class AccountingServiceTest {
    private val activityDataService: ActivityDataService = mock()
    private val balanceDataService: BalanceDataService = mock()
    private val coffeePriceService: CoffeePriceService = mock()
    private val coffeeConsumptionDataService: CoffeeConsumptionDataService = mock()
    private val coffeeConsumptionService: CoffeeConsumptionService = mock()
    private val userDataService: UserDataService = mock()

    private val service =
        AccountingServiceImpl(
            activityDataService,
            balanceDataService,
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
        type: ActivityEntryType,
        amountCents: Long,
        runningBalanceCents: Long
    ) = ActivityEntry(
        type = type,
        id = UUID.fromString("00000000-0000-0000-0000-000000000001"),
        createdAt = LocalDateTime.now(),
        createdBy = "max",
        note = null,
        amountCents = amountCents,
        runningBalanceCents = runningBalanceCents
    )

    @Test
    fun `userSummary composes the count, price, balance, kitty, and activity for the owner`() {
        val activity = listOf(entry(ActivityEntryType.CONSUMPTION, -50, -50))
        whenever(userDataService.getById(memberId)).thenReturn(member)
        whenever(activityDataService.userActivity(memberId, "max")).thenReturn(activity)
        whenever(
            coffeeConsumptionDataService.getByUserId(memberId)
        ).thenReturn(CoffeeConsumption(user = member, count = 1))
        whenever(coffeePriceService.getCurrent()).thenReturn(CoffeePrice(amountCents = 50))
        whenever(balanceDataService.kittyBalanceCents()).thenReturn(0L)
        whenever(coffeeConsumptionService.cancellableIncrement(memberId, member))
            .thenReturn(CancellableIncrement(LocalDateTime.now(), 50))

        val summary = service.userSummary(memberId, 10, 0, member)

        assertThat(summary.count).isEqualTo(1)
        assertThat(summary.priceCents).isEqualTo(50)
        assertThat(summary.balanceCents).isEqualTo(-50)
        assertThat(summary.kittyBalanceCents).isEqualTo(0)
        assertThat(summary.cancellable).isTrue()
        assertThat(summary.activity).hasSize(1)
    }

    @Test
    fun `userSummary reports cancellable false when the count is zero`() {
        // even if a stale increment is still within the grace period, a zero count is not cancellable
        whenever(userDataService.getById(memberId)).thenReturn(member)
        whenever(activityDataService.userActivity(memberId, "max")).thenReturn(emptyList())
        whenever(
            coffeeConsumptionDataService.getByUserId(memberId)
        ).thenReturn(CoffeeConsumption(user = member, count = 0))
        whenever(coffeePriceService.getCurrent()).thenReturn(CoffeePrice(amountCents = 50))

        val summary = service.userSummary(memberId, 10, 0, member)

        assertThat(summary.count).isEqualTo(0)
        assertThat(summary.cancellable).isFalse()
        // the count gate short-circuits, so the cancellable lookup is never even consulted
        verify(coffeeConsumptionService, never()).cancellableIncrement(any(), any())
    }

    @Test
    fun `userSummary by a non-owner non-admin throws ForbiddenException`() {
        val stranger = member.copy(id = UUID(0L, 7L), loginName = "other")
        whenever(userDataService.getById(memberId)).thenReturn(member)

        assertThrows<ForbiddenException> { service.userSummary(memberId, 10, 0, stranger) }
    }

    @Test
    fun `kittyBalanceCents returns the maintained kitty projection`() {
        whenever(balanceDataService.kittyBalanceCents()).thenReturn(300)

        assertThat(service.kittyBalanceCents()).isEqualTo(300)
    }

    @Test
    fun `allBalances by a non-admin throws ForbiddenException`() {
        assertThrows<ForbiddenException> { service.allBalances(member) }
    }

    @Test
    fun `allBalances returns each member's count and balance for an admin`() {
        whenever(userDataService.getAll()).thenReturn(listOf(member))
        whenever(balanceDataService.allUserBalancesCents()).thenReturn(mapOf(memberId to -50L))
        whenever(
            coffeeConsumptionDataService.getByUserId(memberId)
        ).thenReturn(CoffeeConsumption(user = member, count = 1))

        val balances = service.allBalances(admin)

        assertThat(balances).singleElement()
        assertThat(balances.first().count).isEqualTo(1)
        assertThat(balances.first().balanceCents).isEqualTo(-50)
    }

    @Test
    fun `allBalances reports a zero balance for a member absent from the projection`() {
        whenever(userDataService.getAll()).thenReturn(listOf(member))
        whenever(balanceDataService.allUserBalancesCents()).thenReturn(emptyMap())
        whenever(
            coffeeConsumptionDataService.getByUserId(memberId)
        ).thenReturn(CoffeeConsumption(user = member, count = 0))

        assertThat(service.allBalances(admin).first().balanceCents).isEqualTo(0)
    }
}
