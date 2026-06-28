package de.seuhd.campuscoffee

import de.seuhd.campuscoffee.configuration.FixturesProperties
import de.seuhd.campuscoffee.domain.model.CoffeeConsumption
import de.seuhd.campuscoffee.domain.model.Expense
import de.seuhd.campuscoffee.domain.model.Payment
import de.seuhd.campuscoffee.domain.model.Role
import de.seuhd.campuscoffee.domain.model.User
import de.seuhd.campuscoffee.domain.ports.api.CoffeeConsumptionService
import de.seuhd.campuscoffee.domain.ports.api.CoffeePriceService
import de.seuhd.campuscoffee.domain.ports.api.ExpenseService
import de.seuhd.campuscoffee.domain.ports.api.PaymentService
import de.seuhd.campuscoffee.domain.ports.api.UserService
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import java.util.UUID

/**
 * Unit tests for the dev demo data loader's contract, mocking the services. They assert that the loader
 * creates its full set of extra demo users, layers consumption, bean-purchase, and deposit history on
 * top of them (plus one kitty float), deactivates a user marked inactive only after seeding their history,
 * and is idempotent: a second [DevDemoDataLoader.loadDemoData] call skips because the demo users already
 * exist.
 */
class DevDemoDataLoaderTest {
    private val userService = mock<UserService>()
    private val coffeeConsumptionService = mock<CoffeeConsumptionService>()
    private val coffeePriceService = mock<CoffeePriceService>()
    private val expenseService = mock<ExpenseService>()
    private val paymentService = mock<PaymentService>()

    // the default loader has demo seeding enabled (the production default); the gate is exercised separately
    private val loader =
        DevDemoDataLoader(
            userService,
            coffeeConsumptionService,
            coffeePriceService,
            expenseService,
            paymentService,
            FixturesProperties(demoDataOnStartup = true)
        )

    private val admin =
        User(
            id = UUID(0L, 1L),
            loginName = "jane_doe",
            emailAddress = "jane@se.de",
            firstName = "Jane",
            lastName = "Doe",
            role = Role.ADMIN,
            active = true
        )

    // an existing fixture user the loader resolves by login to enrich with a varied history; built with a
    // persisted id so user.persistedId resolves
    private fun fixtureUser(login: String): User =
        admin.copy(id = UUID.randomUUID(), loginName = login, role = Role.USER)

    // The loader builds each user through userService.upsert; echo the argument back with a stable id so
    // the caller has a persisted user (with an id) to seed history against.
    private fun stubCreatePath() {
        whenever(userService.getByLoginName("jane_doe")).thenReturn(admin)
        // the loader also resolves the primary demo user and the enriched fixture users by login to seed
        // their histories: each must be an active user with a persisted id
        whenever(userService.getByLoginName("maxmustermann")).thenReturn(fixtureUser("maxmustermann"))
        whenever(userService.getByLoginName("student2023")).thenReturn(fixtureUser("student2023"))
        whenever(userService.getByLoginName("lisa_lee")).thenReturn(fixtureUser("lisa_lee"))
        whenever(userService.getByLoginName("olivia_lee")).thenReturn(fixtureUser("olivia_lee"))
        whenever(userService.upsert(any())).thenAnswer { invocation ->
            val user = invocation.arguments[0] as User
            if (user.id != null) user else user.copy(id = UUID.randomUUID())
        }
        whenever(coffeeConsumptionService.createForUser(any())).thenReturn(mock<CoffeeConsumption>())
        whenever(coffeeConsumptionService.applyDelta(any(), eq(1), any())).thenReturn(mock<CoffeeConsumption>())
        whenever(expenseService.recordOwn(any(), any(), any(), any())).thenReturn(mock<Expense>())
        whenever(paymentService.recordDeposit(any(), any(), any(), any())).thenReturn(mock<Payment>())
        whenever(paymentService.adjustKitty(any(), any(), any())).thenReturn(mock<Payment>())
    }

    @Test
    fun `loadDemoData seeds the nine extra demo users on an empty fixture set bringing the total to fourteen`() {
        // the database holds only the five seeded fixtures (none of them a demo user)
        whenever(userService.getAll()).thenReturn(listOf(admin))
        stubCreatePath()

        loader.loadDemoData()

        // nine demo users are each created via upsert (one create per user); the two inactive specs are
        // each upserted a second time to deactivate them after their history is seeded; and the extra empty
        // user (new_user) is created via one more upsert -> 9 + 2 + 1 = 12 upserts
        verify(userService, times(12)).upsert(any())
        // every demo user plus the empty user gets a consumption row at zero -> 9 + 1 = 10
        verify(coffeeConsumptionService, times(10)).createForUser(any())
    }

    @Test
    fun `loadDemoData seeds consumption, expense, and deposit history plus one kitty float`() {
        whenever(userService.getAll()).thenReturn(listOf(admin))
        stubCreatePath()

        loader.loadDemoData()

        // the demo specs add coffees, own bean purchases, and user deposits, so each seed path runs
        verify(coffeeConsumptionService, atLeastOnce()).applyDelta(any(), eq(1), any())
        verify(expenseService, atLeastOnce()).recordOwn(any(), any(), any(), any())
        verify(paymentService, atLeastOnce()).recordDeposit(any(), any(), any(), any())
        // exactly one initial kitty float, recorded against the resolved fixture admin
        verify(paymentService, times(1)).adjustKitty(any(), any(), eq(admin))
    }

    @Test
    fun `loadDemoData deactivates an inactive demo user only after seeding their history`() {
        whenever(userService.getAll()).thenReturn(listOf(admin))
        stubCreatePath()

        loader.loadDemoData()

        // a spec marked inactive is created active (so its consumption and purchase seeding succeeds), then
        // deactivated by a follow-up upsert -> at least one upsert carries active = false
        verify(userService, atLeastOnce()).upsert(argThat { active == false })
    }

    @Test
    fun `loadDemoData skips when the demo users already exist`() {
        // the first demo user is already present (a restart without a reset), so the loader is a no-op
        val existingDemoUser =
            admin.copy(id = UUID(0L, 2L), loginName = "anna_schneider", role = Role.USER)
        whenever(userService.getAll()).thenReturn(listOf(admin, existingDemoUser))

        loader.loadDemoData()

        // none of the seeding paths run on the second (idempotent) call
        verify(userService, never()).upsert(any())
        verify(coffeeConsumptionService, never()).createForUser(any())
        verify(paymentService, never()).adjustKitty(any(), any(), any())
    }

    @Test
    fun `run skips all seeding when demo-data-on-startup is false`() {
        val gatedLoader =
            DevDemoDataLoader(
                userService,
                coffeeConsumptionService,
                coffeePriceService,
                expenseService,
                paymentService,
                FixturesProperties(demoDataOnStartup = false)
            )

        gatedLoader.run()

        // the gate short-circuits before any service is touched (not even the getAll() existence probe)
        verifyNoInteractions(
            userService,
            coffeeConsumptionService,
            coffeePriceService,
            expenseService,
            paymentService
        )
    }

    @Test
    fun `run seeds the demo data when demo-data-on-startup is true`() {
        whenever(userService.getAll()).thenReturn(listOf(admin))
        stubCreatePath()

        loader.run()

        // run() delegates to loadDemoData() when the gate is open, so the seeding paths execute
        verify(userService, atLeastOnce()).upsert(any())
    }
}
