package de.seuhd.campuscoffee.data.persistence.eventsourcing

import de.seuhd.campuscoffee.domain.model.ActivityEntryType
import de.seuhd.campuscoffee.domain.model.CoffeeConsumption
import de.seuhd.campuscoffee.domain.model.CoffeePrice
import de.seuhd.campuscoffee.domain.model.Expense
import de.seuhd.campuscoffee.domain.model.Payment
import de.seuhd.campuscoffee.domain.model.Role
import de.seuhd.campuscoffee.domain.model.User
import de.seuhd.campuscoffee.domain.model.persistedId
import de.seuhd.campuscoffee.domain.ports.data.ActivityDataService
import de.seuhd.campuscoffee.domain.ports.data.CoffeePriceDataService
import de.seuhd.campuscoffee.domain.ports.data.ExpenseDataService
import de.seuhd.campuscoffee.domain.ports.data.PaymentDataService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

/**
 * Integration tests for [ActivityDataServiceImpl]: the user and kitty history projections walked straight
 * from the event log. Each write is attributed to the "SYSTEM" actor (no SecurityContext in a data test),
 * so a consumption write is an "owner step" when the activity is read with ownerLogin = "SYSTEM". These drive
 * the INSERT/UPDATE/DELETE branches of the expense and payment walks, the owner-undo and admin-override
 * consumption branches, and the price-at-time valuation.
 */
class ActivityDataServiceIntegrationTest : AbstractEventSourcingDataIntegrationTest() {
    @Autowired
    private lateinit var activityDataService: ActivityDataService

    @Autowired
    private lateinit var coffeePriceDataService: CoffeePriceDataService

    @Autowired
    private lateinit var expenseDataService: ExpenseDataService

    @Autowired
    private lateinit var paymentDataService: PaymentDataService

    private val systemActor = "SYSTEM"

    // The base clearDatabase (run before each test) deletes consumptions and users, but the expense and
    // payment FKs to users are RESTRICT, so clear those (and the independent price) after each test to leave
    // nothing the next test's base cleanup would trip over.
    @AfterEach
    fun clearMoneyTables() {
        expenseDataService.clear()
        paymentDataService.clear()
        coffeePriceDataService.clear()
    }

    private fun seedMember(): User =
        userDataService.upsert(
            User(
                loginName = "member",
                emailAddress = "member@se.de",
                firstName = "Mem",
                lastName = "Ber",
                role = Role.USER,
                active = true,
                capabilityToken = "token-member-${UUID.randomUUID()}",
                passwordHash = "{noop}hash"
            )
        )

    // The price is a single global row (a unique singleton guard rejects a second insert), so a price change
    // updates the existing row in place (mirroring the production CoffeePriceServiceImpl) rather than
    // inserting a new one. The append-only log still records each change, so the price history is preserved.
    private fun seedPrice(amountCents: Int) =
        coffeePriceDataService.upsert(
            coffeePriceDataService.findCurrent()?.copy(amountCents = amountCents)
                ?: CoffeePrice(amountCents = amountCents)
        )

    private fun balanceOf(user: User): Long =
        activityDataService.userActivity(user.persistedId, systemActor).lastOrNull()?.runningBalanceCents ?: 0L

    private fun kittyBalance(): Long = activityDataService.kittyHistory().lastOrNull()?.runningBalanceCents ?: 0L

    @Test
    fun `each coffee is valued at the price in effect at its append position`() {
        val member = seedMember()
        var consumption = coffeeConsumptionDataService.upsert(CoffeeConsumption(user = member, count = 0))
        seedPrice(50)
        consumption = coffeeConsumptionDataService.upsert(consumption.copy(count = 1))
        seedPrice(70)
        coffeeConsumptionDataService.upsert(consumption.copy(count = 2))

        // a coffee at 50 then a coffee at 70 -> the user owes 120
        assertThat(balanceOf(member)).isEqualTo(-120)
    }

    @Test
    fun `a member undo credits exactly the price of the increment it reverses`() {
        val member = seedMember()
        seedPrice(50)
        var consumption = coffeeConsumptionDataService.upsert(CoffeeConsumption(user = member, count = 0))
        seedPrice(70)
        consumption = coffeeConsumptionDataService.upsert(consumption.copy(count = 1)) // owner +1 at 70
        coffeeConsumptionDataService.upsert(consumption.copy(count = 0)) // owner undo, credited at 70

        assertThat(balanceOf(member)).isEqualTo(0)
        val types = activityDataService.userActivity(member.persistedId, systemActor).map { it.type }
        assertThat(types).containsExactly(ActivityEntryType.CONSUMPTION, ActivityEntryType.CONSUMPTION_CANCEL)
    }

    @Test
    fun `an admin override is valued as a lump when read with a different owner login`() {
        val member = seedMember()
        seedPrice(50)
        val consumption = coffeeConsumptionDataService.upsert(CoffeeConsumption(user = member, count = 0))
        coffeeConsumptionDataService.upsert(consumption.copy(count = 3)) // a +3 jump

        // reading with a non-matching owner login treats the system write as an admin override (a lump)
        val activity = activityDataService.userActivity(member.persistedId, "someone-else")
        assertThat(activity.last().runningBalanceCents).isEqualTo(-150)
        assertThat(activity.last().type).isEqualTo(ActivityEntryType.CONSUMPTION)
    }

    @Test
    fun `an admin override that lowers the count credits the member by the removed cups`() {
        val member = seedMember()
        seedPrice(50)
        val consumption = coffeeConsumptionDataService.upsert(CoffeeConsumption(user = member, count = 4))

        // read as an admin override (non-matching owner): a +4 lump, then lower to 1 (a -3 lump, a credit)
        coffeeConsumptionDataService.upsert(consumption.copy(count = 1))

        val activity = activityDataService.userActivity(member.persistedId, "an-admin")
        // 4 cups at 50 = -200, then -3 cups credited back = +150 -> -50
        assertThat(activity.last().runningBalanceCents).isEqualTo(-50)
        assertThat(activity).hasSize(2)
    }

    @Test
    fun `an admin override that lowers the count trims the member's cancellable increments`() {
        val member = seedMember()
        seedPrice(50)
        var consumption = coffeeConsumptionDataService.upsert(CoffeeConsumption(user = member, count = 0))
        // two owner +1 steps, so two increments sit on the undo stack
        consumption = coffeeConsumptionDataService.upsert(consumption.copy(count = 1))
        consumption = coffeeConsumptionDataService.upsert(consumption.copy(count = 2))
        assertThat(activityDataService.lastCancellableIncrement(member.persistedId, systemActor)).isNotNull()

        // a count drop of more than one is never an owner ±1 step, so it is an admin override-down that trims
        // the undo stack: dropping by two removes both outstanding increments, leaving nothing to cancel
        coffeeConsumptionDataService.upsert(consumption.copy(count = 0))

        assertThat(activityDataService.lastCancellableIncrement(member.persistedId, systemActor)).isNull()
    }

    @Test
    fun `a cancellable increment carries the price of the most recent owner increment`() {
        val member = seedMember()
        seedPrice(50)
        var consumption = coffeeConsumptionDataService.upsert(CoffeeConsumption(user = member, count = 0))
        consumption = coffeeConsumptionDataService.upsert(consumption.copy(count = 1)) // +1 at 50
        seedPrice(200)
        coffeeConsumptionDataService.upsert(consumption.copy(count = 2)) // +1 at 200, the newest increment

        // the undo targets the most recent owner increment, so it would credit its price (200)
        val cancellable = activityDataService.lastCancellableIncrement(member.persistedId, systemActor)
        assertThat(cancellable).isNotNull()
        assertThat(cancellable!!.priceCents).isEqualTo(200)
    }

    @Test
    fun `an expense credits the private portion and a correction adjusts to the new portion`() {
        val member = seedMember()
        seedPrice(50)
        var expense =
            expenseDataService.upsert(
                Expense(
                    buyer = member,
                    weightGrams = 1000,
                    amountCents = 900,
                    privateAmountCents = 400,
                    kittyAmountCents = 500
                )
            )
        assertThat(balanceOf(member)).isEqualTo(400)
        assertThat(kittyBalance()).isEqualTo(-500)

        expense =
            expenseDataService.upsert(
                expense.copy(amountCents = 1000, privateAmountCents = 700, kittyAmountCents = 300)
            )

        assertThat(balanceOf(member)).isEqualTo(700)
        assertThat(kittyBalance()).isEqualTo(-300)
    }

    @Test
    fun `a private-only expense does not move the kitty`() {
        val member = seedMember()
        seedPrice(50)
        expenseDataService.upsert(
            Expense(
                buyer = member,
                weightGrams = 500,
                amountCents = 300,
                privateAmountCents = 300,
                kittyAmountCents = 0
            )
        )

        assertThat(balanceOf(member)).isEqualTo(300)
        // no kitty-funded portion, so the kitty stays at zero and the kitty history has no entry
        assertThat(kittyBalance()).isEqualTo(0)
        assertThat(activityDataService.kittyHistory()).isEmpty()
    }

    @Test
    fun `deleting an expense reverses its private credit and kitty draw`() {
        val member = seedMember()
        seedPrice(50)
        val expense =
            expenseDataService.upsert(
                Expense(
                    buyer = member,
                    weightGrams = 1000,
                    amountCents = 900,
                    privateAmountCents = 400,
                    kittyAmountCents = 500
                )
            )
        assertThat(balanceOf(member)).isEqualTo(400)
        assertThat(kittyBalance()).isEqualTo(-500)

        expenseDataService.delete(expense.persistedId)

        // the DELETE event carries the buyer id, so the user activity matches and reverses it too
        assertThat(balanceOf(member)).isEqualTo(0)
        assertThat(kittyBalance()).isEqualTo(0)
    }

    @Test
    fun `a split expense carries both portions on the member and the kitty history entry`() {
        val member = seedMember()
        seedPrice(50)
        expenseDataService.upsert(
            Expense(
                buyer = member,
                weightGrams = 1000,
                amountCents = 900,
                privateAmountCents = 400,
                kittyAmountCents = 500
            )
        )

        val memberEntry =
            activityDataService.userActivity(member.persistedId, systemActor).first {
                it.type == ActivityEntryType.PRIVATE_EXPENSE
            }
        assertThat(memberEntry.amountCents).isEqualTo(400)
        assertThat(memberEntry.privateAmountCents).isEqualTo(400)
        assertThat(memberEntry.kittyAmountCents).isEqualTo(500)

        val kittyEntry = activityDataService.kittyHistory().first { it.type == ActivityEntryType.KITTY_EXPENSE }
        // the kitty entry's own effect is the negative kitty draw, but it carries the same split breakdown
        assertThat(kittyEntry.amountCents).isEqualTo(-500)
        assertThat(kittyEntry.privateAmountCents).isEqualTo(400)
        assertThat(kittyEntry.kittyAmountCents).isEqualTo(500)
    }

    @Test
    fun `a private-only expense carries no split on the member activity entry`() {
        val member = seedMember()
        seedPrice(50)
        expenseDataService.upsert(
            Expense(
                buyer = member,
                weightGrams = 500,
                amountCents = 300,
                privateAmountCents = 300,
                kittyAmountCents = 0
            )
        )

        val memberEntry =
            activityDataService.userActivity(member.persistedId, systemActor).first {
                it.type == ActivityEntryType.PRIVATE_EXPENSE
            }
        assertThat(memberEntry.privateAmountCents).isNull()
        assertThat(memberEntry.kittyAmountCents).isNull()
    }

    @Test
    fun `a deposit credits the member and feeds the kitty`() {
        val member = seedMember()
        seedPrice(50)
        paymentDataService.upsert(Payment(user = member, amountCents = 1000))

        assertThat(balanceOf(member)).isEqualTo(1000)
        assertThat(kittyBalance()).isEqualTo(1000)
        assertThat(activityDataService.kittyHistory().last().type).isEqualTo(ActivityEntryType.DEPOSIT)
    }

    @Test
    fun `a kitty adjustment feeds the kitty without a member and may be corrected and deleted`() {
        seedPrice(50)
        var adjustment = paymentDataService.upsert(Payment(user = null, amountCents = 1000))
        assertThat(kittyBalance()).isEqualTo(1000)
        assertThat(activityDataService.kittyHistory().last().type).isEqualTo(ActivityEntryType.KITTY_ADJUSTMENT)

        adjustment = paymentDataService.upsert(adjustment.copy(amountCents = 750)) // correct the amount
        assertThat(kittyBalance()).isEqualTo(750)

        paymentDataService.delete(adjustment.persistedId)
        assertThat(kittyBalance()).isEqualTo(0)
    }

    @Test
    fun `re-recording the same count is a no-op on the member balance`() {
        val member = seedMember()
        seedPrice(50)
        var consumption = coffeeConsumptionDataService.upsert(CoffeeConsumption(user = member, count = 1))
        // an upsert that does not change the count records an event with delta 0, which the walk ignores
        consumption = coffeeConsumptionDataService.upsert(consumption.copy(count = 1))

        assertThat(balanceOf(member)).isEqualTo(-50)
        // only the single +1 increment shows on the activity; the no-op upsert adds nothing
        assertThat(activityDataService.userActivity(member.persistedId, systemActor)).hasSize(1)
    }

    @Test
    fun `correcting a deposit adjusts the member balance and the kitty to the new amount`() {
        val member = seedMember()
        seedPrice(50)
        var deposit = paymentDataService.upsert(Payment(user = member, amountCents = 1000))
        assertThat(balanceOf(member)).isEqualTo(1000)
        assertThat(kittyBalance()).isEqualTo(1000)

        deposit = paymentDataService.upsert(deposit.copy(amountCents = 1200))

        // the user balance and the kitty both reflect the corrected amount (the full state)
        assertThat(balanceOf(member)).isEqualTo(1200)
        assertThat(kittyBalance()).isEqualTo(1200)
    }

    @Test
    fun `deleting a deposit reverses the member balance and the kitty contribution`() {
        val member = seedMember()
        seedPrice(50)
        val deposit = paymentDataService.upsert(Payment(user = member, amountCents = 1000))
        assertThat(balanceOf(member)).isEqualTo(1000)
        assertThat(kittyBalance()).isEqualTo(1000)

        paymentDataService.delete(deposit.persistedId)

        // the DELETE event carries the user id, so the user activity reverses the deposit too
        assertThat(balanceOf(member)).isEqualTo(0)
        assertThat(kittyBalance()).isEqualTo(0)
    }

    @Test
    fun `priceHistory returns the price changes oldest-first`() {
        seedPrice(50)
        val current = coffeePriceDataService.findCurrent()!!
        coffeePriceDataService.upsert(current.copy(amountCents = 70))

        assertThat(activityDataService.priceHistory().map { it.amountCents }).containsExactly(50, 70)
    }
}
