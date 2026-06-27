package de.seuhd.campuscoffee

import de.seuhd.campuscoffee.configuration.FixturesProperties
import de.seuhd.campuscoffee.domain.model.CoffeeConsumption
import de.seuhd.campuscoffee.domain.model.CoffeePrice
import de.seuhd.campuscoffee.domain.model.User
import de.seuhd.campuscoffee.domain.ports.api.CoffeeConsumptionService
import de.seuhd.campuscoffee.domain.ports.api.CoffeePriceService
import de.seuhd.campuscoffee.domain.ports.api.ExpenseService
import de.seuhd.campuscoffee.domain.ports.api.PaymentService
import de.seuhd.campuscoffee.domain.ports.api.UserService
import de.seuhd.campuscoffee.domain.ports.system.IdGeneratorService
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Unit tests for the fixture startup loader's guard: it seeds the fixtures only when the database has no
 * users yet.
 */
class FixtureStartupLoaderTest {
    private val userService = mock<UserService>()
    private val coffeeConsumptionService = mock<CoffeeConsumptionService>()
    private val coffeePriceService = mock<CoffeePriceService>()
    private val expenseService = mock<ExpenseService>()
    private val paymentService = mock<PaymentService>()
    private val idGenerator = mock<IdGeneratorService>()
    private val loader =
        FixtureStartupLoader(
            userService,
            coffeeConsumptionService,
            coffeePriceService,
            expenseService,
            paymentService,
            idGenerator,
            FixturesProperties(loadOnStartup = true, resetOnStartup = false)
        )

    @Test
    fun `loadOnStartup seeds the fixtures when the database is empty`() {
        whenever(userService.getAll()).thenReturn(emptyList())
        whenever(userService.upsert(any())).thenAnswer { it.arguments[0] as User }
        whenever(coffeeConsumptionService.createForUser(any())).thenReturn(mock<CoffeeConsumption>())
        whenever(coffeePriceService.ensureInitialPrice(any())).thenReturn(mock<CoffeePrice>())

        loader.loadOnStartup()

        verify(userService, atLeastOnce()).upsert(any())
        verify(coffeeConsumptionService, atLeastOnce()).createForUser(any())
        verify(coffeePriceService, atLeastOnce()).ensureInitialPrice(any())
    }

    @Test
    fun `loadOnStartup skips when the database already has users`() {
        whenever(userService.getAll()).thenReturn(listOf(mock<User>()))

        loader.loadOnStartup()

        verify(userService, never()).upsert(any())
    }

    @Test
    fun `loadOnStartup resets and reseeds when reset-on-startup is set, even with existing users`() {
        val resetLoader =
            FixtureStartupLoader(
                userService,
                coffeeConsumptionService,
                coffeePriceService,
                expenseService,
                paymentService,
                idGenerator,
                FixturesProperties(loadOnStartup = true, resetOnStartup = true)
            )
        whenever(userService.getAll()).thenReturn(listOf(mock<User>()))
        whenever(userService.upsert(any())).thenAnswer { it.arguments[0] as User }
        whenever(coffeeConsumptionService.createForUser(any())).thenReturn(mock<CoffeeConsumption>())
        whenever(coffeePriceService.ensureInitialPrice(any())).thenReturn(mock<CoffeePrice>())

        resetLoader.loadOnStartup()

        verify(idGenerator).reset()
        verify(userService).clear()
        verify(userService, atLeastOnce()).upsert(any())
    }
}
