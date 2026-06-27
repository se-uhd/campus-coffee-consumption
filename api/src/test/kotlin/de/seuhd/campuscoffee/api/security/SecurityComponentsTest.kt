package de.seuhd.campuscoffee.api.security

import de.seuhd.campuscoffee.api.security.ActorProviderServiceImpl
import de.seuhd.campuscoffee.api.security.CapabilityTokenAuthenticationFilter
import de.seuhd.campuscoffee.api.security.DomainUserDetailsService
import de.seuhd.campuscoffee.domain.exceptions.NotFoundException
import de.seuhd.campuscoffee.domain.model.Role
import de.seuhd.campuscoffee.domain.model.User
import de.seuhd.campuscoffee.domain.ports.api.UserService
import jakarta.servlet.FilterChain
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.core.userdetails.UsernameNotFoundException
import java.util.UUID

/**
 * Unit tests for the security adapters: the actor provider, the capability token filter, and the user
 * details service, covering the branches the end-to-end system tests do not reach.
 */
class SecurityComponentsTest {
    private val userService = mock<UserService>()

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    private fun user() =
        User(
            id = UUID.randomUUID(),
            loginName = "max",
            emailAddress = "max@se.de",
            firstName = "Max",
            lastName = "M",
            role = Role.USER,
            active = true,
            capabilityToken = "the-token",
            passwordHash = "{noop}hash"
        )

    @Test
    fun `the actor provider returns system when there is no authentication`() {
        assertThat(ActorProviderServiceImpl().currentActor()).isEqualTo("SYSTEM")
    }

    @Test
    fun `the actor provider returns system for an anonymous authentication`() {
        SecurityContextHolder.getContext().authentication =
            AnonymousAuthenticationToken("key", "anonymousUser", listOf(SimpleGrantedAuthority("ROLE_ANONYMOUS")))

        assertThat(ActorProviderServiceImpl().currentActor()).isEqualTo("SYSTEM")
    }

    @Test
    fun `the actor provider returns the principal name when authenticated`() {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken("alice", null, listOf(SimpleGrantedAuthority("ROLE_USER")))

        assertThat(ActorProviderServiceImpl().currentActor()).isEqualTo("alice")
    }

    @Test
    fun `the capability filter authenticates a known token as a ROLE_USER principal`() {
        whenever(userService.findByCapabilityToken("the-token")).thenReturn(user())
        val request = MockHttpServletRequest().apply { addHeader("X-Capability-Token", "the-token") }
        val chain = mock<FilterChain>()

        CapabilityTokenAuthenticationFilter(userService).doFilter(request, MockHttpServletResponse(), chain)

        val authentication = SecurityContextHolder.getContext().authentication!!
        assertThat(authentication.name).isEqualTo("max")
        assertThat(authentication.authorities.map { it.authority }).containsExactly("ROLE_USER")
        verify(chain).doFilter(any(), any())
    }

    @Test
    fun `the capability filter leaves an unknown token unauthenticated`() {
        whenever(userService.findByCapabilityToken("unknown")).thenReturn(null)
        val request = MockHttpServletRequest().apply { addHeader("X-Capability-Token", "unknown") }
        val chain = mock<FilterChain>()

        CapabilityTokenAuthenticationFilter(userService).doFilter(request, MockHttpServletResponse(), chain)

        assertThat(SecurityContextHolder.getContext().authentication).isNull()
        verify(chain).doFilter(any(), any())
    }

    @Test
    fun `the capability filter ignores a request without the token header`() {
        val chain = mock<FilterChain>()

        CapabilityTokenAuthenticationFilter(
            userService
        ).doFilter(MockHttpServletRequest(), MockHttpServletResponse(), chain)

        assertThat(SecurityContextHolder.getContext().authentication).isNull()
        verify(chain).doFilter(any(), any())
    }

    @Test
    fun `the user details service defaults a null role to ROLE_USER`() {
        whenever(userService.getByLoginName("norole")).thenReturn(user().copy(role = null))

        val details = DomainUserDetailsService(userService).loadUserByUsername("norole")

        assertThat(details.authorities.map { it.authority }).containsExactly("ROLE_USER")
    }

    @Test
    fun `the user details service rejects a user without a stored password hash`() {
        whenever(userService.getByLoginName("nohash")).thenReturn(user().copy(passwordHash = null))

        assertThatThrownBy { DomainUserDetailsService(userService).loadUserByUsername("nohash") }
            .isInstanceOf(UsernameNotFoundException::class.java)
    }

    @Test
    fun `the user details service rejects an unknown login name`() {
        whenever(
            userService.getByLoginName("ghost")
        ).thenThrow(NotFoundException(User::class.java, "loginName", "ghost"))

        assertThatThrownBy { DomainUserDetailsService(userService).loadUserByUsername("ghost") }
            .isInstanceOf(UsernameNotFoundException::class.java)
    }
}
