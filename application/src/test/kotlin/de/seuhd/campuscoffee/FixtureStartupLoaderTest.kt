package de.seuhd.campuscoffee

import de.seuhd.campuscoffee.domain.model.CoffeeConsumption
import de.seuhd.campuscoffee.domain.model.User
import de.seuhd.campuscoffee.domain.ports.api.CoffeeConsumptionService
import de.seuhd.campuscoffee.domain.ports.api.UserService
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
    private val loader = FixtureStartupLoader(userService, coffeeConsumptionService)

    @Test
    fun `loadOnStartup seeds the fixtures when the database is empty`() {
        whenever(userService.getAll()).thenReturn(emptyList())
        whenever(userService.upsert(any())).thenAnswer { it.arguments[0] as User }
        whenever(coffeeConsumptionService.createForUser(any())).thenReturn(mock<CoffeeConsumption>())

        loader.loadOnStartup()

        verify(userService, atLeastOnce()).upsert(any())
        verify(coffeeConsumptionService, atLeastOnce()).createForUser(any())
    }

    @Test
    fun `loadOnStartup skips when the database already has users`() {
        whenever(userService.getAll()).thenReturn(listOf(mock<User>()))

        loader.loadOnStartup()

        verify(userService, never()).upsert(any())
    }
}
