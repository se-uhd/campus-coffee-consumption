package de.seuhd.campuscoffee.api.security

import de.seuhd.campuscoffee.domain.model.User
import de.seuhd.campuscoffee.domain.ports.api.UserService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalStateException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder

/**
 * Unit tests for [CurrentUserProvider], which resolves the authenticated principal to the domain
 * [User]. The cases cover a resolved principal and the two guard branches (no authentication on the
 * context, and an unauthenticated token), which the security filter chain would normally reject before
 * a controller runs.
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
    fun `currentUser throws when no authentication is present on the context`() {
        assertThatIllegalStateException().isThrownBy { provider.currentUser() }
    }

    @Test
    fun `currentUser throws for an unauthenticated token`() {
        // an anonymous token reports isAuthenticated == false, so the guard rejects it
        SecurityContextHolder.getContext().authentication =
            AnonymousAuthenticationToken("key", "anonymous", listOf(SimpleGrantedAuthority("ROLE_ANONYMOUS")))
                .apply { isAuthenticated = false }

        assertThatIllegalStateException().isThrownBy { provider.currentUser() }
    }
}
