package de.seuhd.campuscoffee.security

import de.seuhd.campuscoffee.domain.exceptions.NotFoundException
import de.seuhd.campuscoffee.domain.model.Role
import de.seuhd.campuscoffee.domain.ports.api.UserService
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.stereotype.Service
import org.springframework.security.core.userdetails.User as SpringUser

/**
 * Loads a user by login name and adapts it to a Spring Security [UserDetails], with the stored password
 * hash and the single `ROLE_<role>` authority derived from the user's role. This is the bridge between the
 * domain `User` and Spring Security, used by the admin-login path (the token endpoint); the authorization
 * rules themselves are defined in [SecurityConfig].
 *
 * The [UserDetails] is built **disabled** for an inactive user, so the `DaoAuthenticationProvider` rejects
 * a deactivated admin's `POST /api/auth/token` with a `DisabledException` (surfaced as 401 by the global
 * exception handler). This is the login-time half of the admin-deactivation lockout; an in-flight JWT
 * minted before deactivation is rejected separately when the request principal is resolved to a domain user.
 */
@Service
class CampusUserDetailsService(
    private val userService: UserService
) : UserDetailsService {
    override fun loadUserByUsername(username: String): UserDetails {
        val user =
            try {
                userService.getByLoginName(username)
            } catch (e: NotFoundException) {
                throw UsernameNotFoundException("No user with login name '$username'.", e)
            }

        // a user always has a role; default defensively to the least-privileged USER rather than throw
        val role = user.role ?: Role.USER
        val authorities = listOf(SimpleGrantedAuthority("ROLE_${role.name}"))

        // A user without a stored hash has no usable credentials, so they cannot authenticate. Surface
        // that as an unknown user rather than building an account that no password could ever match.
        val storedHash =
            user.passwordHash
                ?: throw UsernameNotFoundException("User '$username' has no password set.")

        return SpringUser
            .withUsername(user.loginName)
            .password(storedHash)
            .authorities(authorities)
            // a deactivated user is disabled, so the DaoAuthenticationProvider refuses the login
            .disabled(user.active == false)
            .build()
    }
}
