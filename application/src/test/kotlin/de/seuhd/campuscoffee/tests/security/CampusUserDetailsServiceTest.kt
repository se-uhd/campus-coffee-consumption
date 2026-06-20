package de.seuhd.campuscoffee.tests.security

import de.seuhd.campuscoffee.domain.exceptions.NotFoundException
import de.seuhd.campuscoffee.domain.model.Role
import de.seuhd.campuscoffee.domain.model.User
import de.seuhd.campuscoffee.domain.ports.api.UserService
import de.seuhd.campuscoffee.security.CampusUserDetailsService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.whenever
import org.springframework.security.core.userdetails.UsernameNotFoundException
import java.util.UUID

/**
 * Unit tests for [CampusUserDetailsService], which adapts a domain [User] to a Spring Security
 * UserDetails: the single role becomes a `ROLE_<role>` authority, the stored hash becomes the password,
 * and both a missing hash and an unknown login name map to [UsernameNotFoundException].
 */
@ExtendWith(MockitoExtension::class)
class CampusUserDetailsServiceTest {
    @Mock
    private lateinit var userService: UserService

    private val service by lazy { CampusUserDetailsService(userService) }

    private fun user(
        role: Role?,
        passwordHash: String?
    ) = User(
        id = UUID(0L, 1L),
        loginName = "jane_doe",
        emailAddress = "jane.doe@se.uni-heidelberg.de",
        firstName = "Jane",
        lastName = "Doe",
        role = role,
        passwordHash = passwordHash
    )

    @Test
    fun `loadUserByUsername maps the role to a ROLE_ authority and exposes the stored hash`() {
        whenever(userService.getByLoginName("jane_doe")).thenReturn(user(Role.ADMIN, "{bcrypt}HASH"))

        val details = service.loadUserByUsername("jane_doe")

        assertThat(details.username).isEqualTo("jane_doe")
        assertThat(details.password).isEqualTo("{bcrypt}HASH")
        assertThat(details.authorities.map { it.authority }).containsExactly("ROLE_ADMIN")
    }

    @Test
    fun `loadUserByUsername throws UsernameNotFoundException when the user has no stored hash`() {
        whenever(userService.getByLoginName("jane_doe")).thenReturn(user(Role.USER, null))

        assertThatThrownBy { service.loadUserByUsername("jane_doe") }
            .isInstanceOf(UsernameNotFoundException::class.java)
    }

    @Test
    fun `loadUserByUsername throws UsernameNotFoundException for an unknown login name`() {
        whenever(userService.getByLoginName("ghost"))
            .thenThrow(NotFoundException(User::class.java, "login name", "ghost"))

        assertThatThrownBy { service.loadUserByUsername("ghost") }
            .isInstanceOf(UsernameNotFoundException::class.java)
    }
}
