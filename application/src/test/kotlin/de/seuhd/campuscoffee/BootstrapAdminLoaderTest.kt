package de.seuhd.campuscoffee

import de.seuhd.campuscoffee.configuration.BootstrapAdminProperties
import de.seuhd.campuscoffee.domain.model.Role
import de.seuhd.campuscoffee.domain.model.User
import de.seuhd.campuscoffee.domain.ports.api.CoffeeConsumptionService
import de.seuhd.campuscoffee.domain.ports.api.UserService
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.UUID

/**
 * Unit tests for the bootstrap-admin startup guard: it creates an admin only when the credentials are
 * configured and no admin exists yet, and rejects a blank, too-short, or partial configuration.
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

    private fun fullProperties() =
        BootstrapAdminProperties(
            loginName = "admin",
            password = "a-password",
            emailAddress = "admin@se.de",
            firstName = "Boot",
            lastName = "Strap"
        )

    @Test
    fun `createBootstrapAdmin does nothing when no credentials are configured`() {
        loader(BootstrapAdminProperties(null, null, null, null, null)).createBootstrapAdmin()

        verify(userService, never()).upsert(any())
    }

    @Test
    fun `createBootstrapAdmin rejects a blank password`() {
        assertThatIllegalArgumentException().isThrownBy {
            loader(fullProperties().copy(password = "")).createBootstrapAdmin()
        }
        verify(userService, never()).upsert(any())
    }

    @Test
    fun `createBootstrapAdmin rejects a blank login name`() {
        assertThatIllegalArgumentException().isThrownBy {
            loader(fullProperties().copy(loginName = "")).createBootstrapAdmin()
        }
        verify(userService, never()).upsert(any())
    }

    @Test
    fun `createBootstrapAdmin rejects a too-short password`() {
        assertThatIllegalArgumentException().isThrownBy {
            loader(fullProperties().copy(password = "short")).createBootstrapAdmin()
        }
        verify(userService, never()).upsert(any())
    }

    @Test
    fun `createBootstrapAdmin does nothing when an admin already exists`() {
        whenever(userService.getAll()).thenReturn(listOf(admin()))

        loader(fullProperties()).createBootstrapAdmin()

        verify(userService, never()).upsert(any())
    }

    @Test
    fun `createBootstrapAdmin creates an admin and its consumption when none exists`() {
        whenever(userService.getAll()).thenReturn(emptyList())
        val created = admin()
        whenever(userService.upsert(any())).thenReturn(created)

        loader(fullProperties()).createBootstrapAdmin()

        verify(userService).upsert(argThat { role == Role.ADMIN && loginName == "admin" })
        verify(coffeeConsumptionService).createForUser(created)
    }
}
