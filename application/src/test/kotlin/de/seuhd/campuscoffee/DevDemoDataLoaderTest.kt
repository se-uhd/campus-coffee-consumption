package de.seuhd.campuscoffee

import de.seuhd.campuscoffee.domain.model.CoffeeConsumption
import de.seuhd.campuscoffee.domain.model.Expense
import de.seuhd.campuscoffee.domain.model.Payment
import de.seuhd.campuscoffee.domain.model.Role
import de.seuhd.campuscoffee.domain.model.User
import de.seuhd.campuscoffee.domain.ports.api.CoffeeConsumptionService
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
import org.mockito.kotlin.whenever
import java.util.UUID

/**
 * Unit tests for the dev demo data loader's contract, mocking the services. They assert that the loader
 * creates its full set of extra demo members, layers consumption, bean-purchase, and settlement history on
 * top of them (plus one kitty float), deactivates a member marked inactive only after seeding their history,
 * and is idempotent: a second [DevDemoDataLoader.loadDemoData] call skips because the demo members already
 * exist.
 */
class DevDemoDataLoaderTest {
    private val userService = mock<UserService>()
    private val coffeeConsumptionService = mock<CoffeeConsumptionService>()
    private val expenseService = mock<ExpenseService>()
    private val paymentService = mock<PaymentService>()

    private val loader =
        DevDemoDataLoader(userService, coffeeConsumptionService, expenseService, paymentService)

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

    // an existing fixture member the loader resolves by login to enrich with a varied history; built with a
    // persisted id so member.persistedId resolves
    private fun fixtureMember(login: String): User =
        admin.copy(id = UUID.randomUUID(), loginName = login, role = Role.USER)

    // The loader builds each member through userService.upsert; echo the argument back with a stable id so
    // the caller has a persisted member (with an id) to seed history against.
    private fun stubCreatePath() {
        whenever(userService.getByLoginName("jane_doe")).thenReturn(admin)
        // the loader also resolves the primary demo member and the enriched fixture members by login to seed
        // their histories: each must be an active member with a persisted id
        whenever(userService.getByLoginName("maxmustermann")).thenReturn(fixtureMember("maxmustermann"))
        whenever(userService.getByLoginName("student2023")).thenReturn(fixtureMember("student2023"))
        whenever(userService.getByLoginName("lisa_lee")).thenReturn(fixtureMember("lisa_lee"))
        whenever(userService.getByLoginName("olivia_lee")).thenReturn(fixtureMember("olivia_lee"))
        whenever(userService.upsert(any())).thenAnswer { invocation ->
            val user = invocation.arguments[0] as User
            if (user.id != null) user else user.copy(id = UUID.randomUUID())
        }
        whenever(coffeeConsumptionService.createForUser(any())).thenReturn(mock<CoffeeConsumption>())
        whenever(coffeeConsumptionService.applyDelta(any(), eq(1), any())).thenReturn(mock<CoffeeConsumption>())
        whenever(expenseService.recordOwn(any(), any(), any(), any())).thenReturn(mock<Expense>())
        whenever(paymentService.recordSettlement(any(), any(), any(), any())).thenReturn(mock<Payment>())
        whenever(paymentService.adjustKitty(any(), any(), any())).thenReturn(mock<Payment>())
    }

    @Test
    fun `loadDemoData seeds the nine extra demo members on an empty fixture set bringing the total to fourteen`() {
        // the database holds only the five seeded fixtures (none of them a demo member)
        whenever(userService.getAll()).thenReturn(listOf(admin))
        stubCreatePath()

        loader.loadDemoData()

        // nine demo members are each created via upsert (one create per member); the two inactive specs are
        // each upserted a second time to deactivate them after their history is seeded; and the extra empty
        // member (new_user) is created via one more upsert -> 9 + 2 + 1 = 12 upserts
        verify(userService, times(12)).upsert(any())
        // every demo member plus the empty member gets a consumption row at zero -> 9 + 1 = 10
        verify(coffeeConsumptionService, times(10)).createForUser(any())
    }

    @Test
    fun `loadDemoData seeds consumption, expense, and settlement history plus one kitty float`() {
        whenever(userService.getAll()).thenReturn(listOf(admin))
        stubCreatePath()

        loader.loadDemoData()

        // the demo specs add coffees, own bean purchases, and member settlements, so each seed path runs
        verify(coffeeConsumptionService, atLeastOnce()).applyDelta(any(), eq(1), any())
        verify(expenseService, atLeastOnce()).recordOwn(any(), any(), any(), any())
        verify(paymentService, atLeastOnce()).recordSettlement(any(), any(), any(), any())
        // exactly one initial kitty float, recorded against the resolved fixture admin
        verify(paymentService, times(1)).adjustKitty(any(), any(), eq(admin))
    }

    @Test
    fun `loadDemoData deactivates an inactive demo member only after seeding their history`() {
        whenever(userService.getAll()).thenReturn(listOf(admin))
        stubCreatePath()

        loader.loadDemoData()

        // a spec marked inactive is created active (so its consumption and purchase seeding succeeds), then
        // deactivated by a follow-up upsert -> at least one upsert carries active = false
        verify(userService, atLeastOnce()).upsert(argThat { active == false })
    }

    @Test
    fun `loadDemoData skips when the demo members already exist`() {
        // the first demo member is already present (a restart without a reset), so the loader is a no-op
        val existingDemoMember =
            admin.copy(id = UUID(0L, 2L), loginName = "anna_schneider", role = Role.USER)
        whenever(userService.getAll()).thenReturn(listOf(admin, existingDemoMember))

        loader.loadDemoData()

        // none of the seeding paths run on the second (idempotent) call
        verify(userService, never()).upsert(any())
        verify(coffeeConsumptionService, never()).createForUser(any())
        verify(paymentService, never()).adjustKitty(any(), any(), any())
    }
}
