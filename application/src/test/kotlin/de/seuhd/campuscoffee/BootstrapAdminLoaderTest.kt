package de.seuhd.campuscoffee

import de.seuhd.campuscoffee.configuration.BootstrapAdminProperties
import de.seuhd.campuscoffee.domain.model.objects.Role
import de.seuhd.campuscoffee.domain.model.objects.User
import de.seuhd.campuscoffee.domain.ports.api.CoffeeConsumptionService
import de.seuhd.campuscoffee.domain.ports.api.UserService
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.UUID

/**
 * Unit tests for the bootstrap-admin startup guard: it creates an admin only when credentials are
 * configured and no admin exists yet.
 */
class BootstrapAdminLoaderTest {
    private val userService = mock<UserService>()
    private val coffeeConsumptionService = mock<CoffeeConsumptionService>()

    private fun loader(properties: BootstrapAdminProperties) =
        BootstrapAdminLoader(userService, coffeeConsumptionService, properties)

    private fun admin() =
        User(
            id = UUID.randomUUID(),
            loginName = "admin",
            emailAddress = "admin@se.de",
            firstName = "Boot",
            lastName = "Strap",
            role = Role.ADMIN
        )

    @Test
    fun `createBootstrapAdmin does nothing when no credentials are configured`() {
        loader(BootstrapAdminProperties(loginName = null, password = null)).createBootstrapAdmin()

        verify(userService, never()).upsert(any())
    }

    @Test
    fun `createBootstrapAdmin does nothing when the password is blank`() {
        loader(BootstrapAdminProperties(loginName = "admin", password = "")).createBootstrapAdmin()

        verify(userService, never()).upsert(any())
    }

    @Test
    fun `createBootstrapAdmin does nothing when the login name is blank`() {
        loader(BootstrapAdminProperties(loginName = "", password = "a-password")).createBootstrapAdmin()

        verify(userService, never()).upsert(any())
    }

    @Test
    fun `createBootstrapAdmin does nothing when an admin already exists`() {
        whenever(userService.getAll()).thenReturn(listOf(admin()))

        loader(BootstrapAdminProperties(loginName = "admin", password = "a-password")).createBootstrapAdmin()

        verify(userService, never()).upsert(any())
    }

    @Test
    fun `createBootstrapAdmin creates an admin and its consumption when none exists`() {
        whenever(userService.getAll()).thenReturn(emptyList())
        val created = admin()
        whenever(userService.upsert(any())).thenReturn(created)

        loader(BootstrapAdminProperties(loginName = "admin", password = "a-password")).createBootstrapAdmin()

        verify(userService).upsert(argThat { role == Role.ADMIN && loginName == "admin" })
        verify(coffeeConsumptionService).createForUser(created)
    }
}
