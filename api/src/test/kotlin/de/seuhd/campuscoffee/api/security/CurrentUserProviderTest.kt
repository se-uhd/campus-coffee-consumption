package de.seuhd.campuscoffee.api.security

import de.seuhd.campuscoffee.domain.exceptions.NotFoundException
import de.seuhd.campuscoffee.domain.model.User
import de.seuhd.campuscoffee.domain.ports.api.UserService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatExceptionOfType
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder

/**
 * Unit tests for [CurrentUserProvider], which resolves the authenticated principal to the domain
 * [User]. The cases cover a resolved principal and the guard branches (no authentication on the context,
 * an unauthenticated token, and a principal that no longer resolves to a user), each of which surfaces as a
 * 401-mapped [AuthenticationCredentialsNotFoundException].
 */
class CurrentUserProviderTest {
    private val userService = mock<UserService>()
    private val provider = CurrentUserProvider(userService)

    @AfterEach
    fun clearContext() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `currentUser resolves the authenticated login name to a domain user`() {
        val user =
            User(loginName = "jane_doe", emailAddress = "jane@uni-heidelberg.de", firstName = "Jane", lastName = "Doe")
        whenever(userService.getByLoginName("jane_doe")).thenReturn(user)
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken("jane_doe", "password", listOf(SimpleGrantedAuthority("ROLE_USER")))

        assertThat(provider.currentUser()).isEqualTo(user)
    }

    @Test
    fun `currentUser throws AuthenticationCredentialsNotFoundException when no authentication is present`() {
        assertThatExceptionOfType(AuthenticationCredentialsNotFoundException::class.java)
            .isThrownBy { provider.currentUser() }
    }

    @Test
    fun `currentUser throws AuthenticationCredentialsNotFoundException for an unauthenticated token`() {
        // an anonymous token reports isAuthenticated == false, so the guard rejects it
        SecurityContextHolder.getContext().authentication =
            AnonymousAuthenticationToken("key", "anonymous", listOf(SimpleGrantedAuthority("ROLE_ANONYMOUS")))
                .apply { isAuthenticated = false }

        assertThatExceptionOfType(AuthenticationCredentialsNotFoundException::class.java)
            .isThrownBy { provider.currentUser() }
    }

    @Test
    fun `currentUser throws AuthenticationCredentialsNotFoundException when the principal no longer resolves`() {
        // a valid token whose login no longer maps to a user (the admin was hard-deleted) reads as a 401,
        // not a 404, so the client re-authenticates
        whenever(userService.getByLoginName("ghost")).thenThrow(NotFoundException(User::class.java, "ghost"))
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken("ghost", "password", listOf(SimpleGrantedAuthority("ROLE_ADMIN")))

        assertThatExceptionOfType(AuthenticationCredentialsNotFoundException::class.java)
            .isThrownBy { provider.currentUser() }
    }
}
