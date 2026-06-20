package de.seuhd.campuscoffee.api.security

import de.seuhd.campuscoffee.domain.model.User
import de.seuhd.campuscoffee.domain.ports.api.UserService
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component

/**
 * Resolves the authenticated principal to the domain [User] acting on the current request.
 *
 * "Current user" is the standard web-security term for the principal authenticated on the request being
 * handled right now. Spring Security keeps that principal in a request-scoped `SecurityContext`
 * ([SecurityContextHolder]), and this class reads it from there.
 *
 * This is the single bridge in the api layer between Spring Security and the domain. The principal's
 * name is the login name (the same value whether authentication arrived via the capability token or a JWT
 * bearer token), and [UserService.getByLoginName] turns it into a domain [User]. Controllers pass that [User]
 * inward so the domain decides ownership and roles without ever touching a Spring `Authentication`.
 */
@Component
class CurrentUserProvider(
    private val userService: UserService
) {
    /**
     * The domain [User] for the authenticated principal of the current request.
     *
     * @return the acting user
     * @throws IllegalStateException if there is no authenticated user (the security filter chain should
     *   already have rejected such a request with 401 before it reaches a controller)
     */
    fun currentUser(): User {
        val authentication =
            SecurityContextHolder.getContext().authentication
                ?: error("No authenticated user is present on the security context.")
        check(authentication.isAuthenticated) { "The current request is not authenticated." }
        return userService.getByLoginName(authentication.name)
    }
}
