package de.seuhd.campuscoffee.domain.implementation

import de.seuhd.campuscoffee.domain.exceptions.ForbiddenException
import de.seuhd.campuscoffee.domain.model.ActivityEntry
import de.seuhd.campuscoffee.domain.model.ActivityEntryType
import de.seuhd.campuscoffee.domain.model.CancellableIncrement
import de.seuhd.campuscoffee.domain.model.CoffeeConsumption
import de.seuhd.campuscoffee.domain.model.CoffeePrice
import de.seuhd.campuscoffee.domain.model.Role
import de.seuhd.campuscoffee.domain.model.SummaryPanel
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
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
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

    // A fixed "now": Thursday 2026-01-15 12:00 UTC, which is 13:00 in Europe/Berlin (CET, the test zone). So
    // the local day starts at 2026-01-14T23:00Z and the local week (Monday 2026-01-12) at 2026-01-11T23:00Z.
    private val clock: Clock = Clock.fixed(Instant.parse("2026-01-15T12:00:00Z"), ZoneOffset.UTC)
    private val summaryProperties = SummaryProperties(ZoneId.of("Europe/Berlin"))

    private val service =
        AccountingServiceImpl(
            activityDataService,
            balanceDataService,
            coffeePriceService,
            coffeeConsumptionDataService,
            coffeeConsumptionService,
            userDataService,
            clock,
            summaryProperties
        )

    private val userId: UUID = UUID(0L, 1L)

    private val user =
        User(
            id = userId,
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
        amountCents: Long = 0,
        runningBalanceCents: Long = 0,
        createdAt: LocalDateTime = LocalDateTime.now(),
        delta: Int? = null
    ) = ActivityEntry(
        type = type,
        id = UUID.fromString("00000000-0000-0000-0000-000000000001"),
        createdAt = createdAt,
        createdBy = "max",
        note = null,
        amountCents = amountCents,
        runningBalanceCents = runningBalanceCents,
        delta = delta
    )

    /** A consumption activity entry at UTC time [at] with the given signed [delta] (negative for an undo). */
    private fun consumption(
        at: String,
        delta: Int,
        type: ActivityEntryType = ActivityEntryType.CONSUMPTION
    ) = entry(type = type, createdAt = LocalDateTime.parse(at), delta = delta)

    /** Stubs the data ports a `userSummary` read needs, with [activity] as the user's full oldest-first walk. */
    private fun stubSummary(
        activity: List<ActivityEntry>,
        count: Int
    ) {
        whenever(userDataService.getById(userId)).thenReturn(user)
        whenever(activityDataService.userActivity(userId, "max")).thenReturn(activity)
        whenever(coffeeConsumptionDataService.getByUserId(userId))
            .thenReturn(CoffeeConsumption(user = user, count = count))
        whenever(coffeePriceService.getCurrent()).thenReturn(CoffeePrice(amountCents = 50))
        whenever(balanceDataService.kittyBalanceCents()).thenReturn(0L)
    }

    @Test
    fun `userSummary composes the count, price, balance, kitty, and activity for the owner`() {
        val activity = listOf(entry(ActivityEntryType.CONSUMPTION, -50, -50))
        whenever(userDataService.getById(userId)).thenReturn(user)
        whenever(activityDataService.userActivity(userId, "max")).thenReturn(activity)
        whenever(
            coffeeConsumptionDataService.getByUserId(userId)
        ).thenReturn(CoffeeConsumption(user = user, count = 1))
        whenever(coffeePriceService.getCurrent()).thenReturn(CoffeePrice(amountCents = 50))
        whenever(balanceDataService.kittyBalanceCents()).thenReturn(0L)
        whenever(coffeeConsumptionService.cancellableIncrement(userId, user))
            .thenReturn(CancellableIncrement(LocalDateTime.now(), 50))

        val summary = service.userSummary(userId, 10, 0, user)

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
        whenever(userDataService.getById(userId)).thenReturn(user)
        whenever(activityDataService.userActivity(userId, "max")).thenReturn(emptyList())
        whenever(
            coffeeConsumptionDataService.getByUserId(userId)
        ).thenReturn(CoffeeConsumption(user = user, count = 0))
        whenever(coffeePriceService.getCurrent()).thenReturn(CoffeePrice(amountCents = 50))

        val summary = service.userSummary(userId, 10, 0, user)

        assertThat(summary.count).isEqualTo(0)
        assertThat(summary.cancellable).isFalse()
        // the count gate short-circuits, so the cancellable lookup is never even consulted
        verify(coffeeConsumptionService, never()).cancellableIncrement(any(), any())
    }

    @Test
    fun `userSummary by a non-owner non-admin throws ForbiddenException`() {
        val stranger = user.copy(id = UUID(0L, 7L), loginName = "other")
        whenever(userDataService.getById(userId)).thenReturn(user)

        assertThrows<ForbiddenException> { service.userSummary(userId, 10, 0, stranger) }
    }

    @Test
    fun `userSummary computes cups today and this week and the first-cup time`() {
        val activity =
            listOf(
                consumption("2026-01-05T10:00:00", 1), // an earlier week: the first cup, outside both windows
                consumption("2026-01-13T10:00:00", 1), // Tuesday: this week but not today
                consumption("2026-01-15T08:00:00", 1) // today
            )
        stubSummary(activity, count = 3)

        val summary = service.userSummary(userId, 10, 0, user)

        assertThat(summary.firstCupAt).isEqualTo(LocalDateTime.parse("2026-01-05T10:00:00"))
        assertThat(summary.cupsToday).isEqualTo(1)
        assertThat(summary.cupsThisWeek).isEqualTo(2)
        assertThat(summary.count).isEqualTo(3)
    }

    @Test
    fun `userSummary reports no cups for a user with none`() {
        stubSummary(emptyList(), count = 0)

        val summary = service.userSummary(userId, 10, 0, user)

        assertThat(summary.firstCupAt).isNull()
        assertThat(summary.cupsToday).isEqualTo(0)
        assertThat(summary.cupsThisWeek).isEqualTo(0)
    }

    @Test
    fun `userSummary nets a same-day undo against the cup in cups today and this week`() {
        val activity =
            listOf(
                consumption("2026-01-15T08:00:00", 1),
                consumption("2026-01-15T09:00:00", -1, ActivityEntryType.CONSUMPTION_CANCEL)
            )
        stubSummary(activity, count = 0)

        val summary = service.userSummary(userId, 10, 0, user)

        assertThat(summary.cupsToday).isEqualTo(0)
        assertThat(summary.cupsThisWeek).isEqualTo(0)
        assertThat(summary.firstCupAt).isEqualTo(LocalDateTime.parse("2026-01-15T08:00:00"))
    }

    @Test
    fun `userSummary clamps a same-day admin down-correction to zero cups today`() {
        // an admin lowering the count today shows as a negative-delta CONSUMPTION; cups today must not go below 0
        val activity = listOf(consumption("2026-01-15T08:00:00", -5))
        stubSummary(activity, count = 0)

        val summary = service.userSummary(userId, 10, 0, user)

        assertThat(summary.cupsToday).isEqualTo(0)
        assertThat(summary.firstCupAt).isNull()
    }

    @Test
    fun `userSummary returns the chosen panel and defaults a null choice to balance`() {
        stubSummary(emptyList(), count = 0)
        assertThat(service.userSummary(userId, 10, 0, user).summaryPanel).isEqualTo(SummaryPanel.BALANCE)

        val cupsUser = user.copy(summaryPanel = SummaryPanel.CUPS)
        whenever(userDataService.getById(userId)).thenReturn(cupsUser)
        assertThat(service.userSummary(userId, 10, 0, cupsUser).summaryPanel).isEqualTo(SummaryPanel.CUPS)
    }

    @Test
    fun `kittyBalanceCents returns the maintained kitty projection`() {
        whenever(balanceDataService.kittyBalanceCents()).thenReturn(300)

        assertThat(service.kittyBalanceCents()).isEqualTo(300)
    }

    @Test
    fun `allBalances by a non-admin throws ForbiddenException`() {
        assertThrows<ForbiddenException> { service.allBalances(user) }
    }

    @Test
    fun `allBalances returns each user's count and balance for an admin`() {
        whenever(userDataService.getAll()).thenReturn(listOf(user))
        whenever(balanceDataService.allUserBalancesCents()).thenReturn(mapOf(userId to -50L))
        whenever(
            coffeeConsumptionDataService.getByUserId(userId)
        ).thenReturn(CoffeeConsumption(user = user, count = 1))

        val balances = service.allBalances(admin)

        assertThat(balances).singleElement()
        assertThat(balances.first().count).isEqualTo(1)
        assertThat(balances.first().balanceCents).isEqualTo(-50)
    }

    @Test
    fun `allBalances reports a zero balance for a user absent from the projection`() {
        whenever(userDataService.getAll()).thenReturn(listOf(user))
        whenever(balanceDataService.allUserBalancesCents()).thenReturn(emptyMap())
        whenever(
            coffeeConsumptionDataService.getByUserId(userId)
        ).thenReturn(CoffeeConsumption(user = user, count = 0))

        assertThat(service.allBalances(admin).first().balanceCents).isEqualTo(0)
    }
}
