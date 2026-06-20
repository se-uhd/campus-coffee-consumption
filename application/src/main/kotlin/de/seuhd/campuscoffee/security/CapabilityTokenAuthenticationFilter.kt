package de.seuhd.campuscoffee.security

import de.seuhd.campuscoffee.domain.ports.api.UserService
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Authenticates a member by their secret capability token. Reads the `X-Coffee-Token` header, resolves it
 * to a [de.seuhd.campuscoffee.domain.model.User] via [UserService.findByCapabilityToken], and on a
 * match sets a `ROLE_USER` principal (the member's login name) on the security context. The capability
 * principal is **always** `ROLE_USER`, never `ROLE_ADMIN`, so an admin's own token grants only
 * self-service, and admin operations require the JWT instead.
 *
 * A missing header leaves the request unauthenticated (a protected endpoint then answers 401 via the entry
 * point). An unknown or rotated token also leaves it unauthenticated, so it likewise yields 401. A
 * deactivated member is still authenticated here (so their reads work); the domain rejects their mutations
 * with 403, keeping the account read-only.
 */
@Component
class CapabilityTokenAuthenticationFilter(
    private val userService: UserService
) : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        val token = request.getHeader(COFFEE_TOKEN_HEADER)
        if (token != null && SecurityContextHolder.getContext().authentication == null) {
            userService.findByCapabilityToken(token)?.let { member ->
                val authentication =
                    UsernamePasswordAuthenticationToken(
                        member.loginName,
                        null,
                        listOf(SimpleGrantedAuthority(ROLE_USER))
                    )
                authentication.details = WebAuthenticationDetailsSource().buildDetails(request)
                SecurityContextHolder.getContext().authentication = authentication
            }
        }
        filterChain.doFilter(request, response)
    }

    private companion object {
        private const val COFFEE_TOKEN_HEADER = "X-Coffee-Token"
        private const val ROLE_USER = "ROLE_USER"
    }
}
