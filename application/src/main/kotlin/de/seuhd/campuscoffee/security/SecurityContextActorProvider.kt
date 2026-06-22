package de.seuhd.campuscoffee.security

import de.seuhd.campuscoffee.domain.ports.ActorProvider
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component

/**
 * The [ActorProvider] adapter, reading the authenticated principal from Spring Security's
 * `SecurityContext`. Returns the principal's login name (a member authenticated by their capability token,
 * or an admin by their JWT), or `"system"` when there is no real principal: an anonymous request, or an
 * off-request flow such as the startup fixtures or bootstrap admin. Takes precedence over the data layer's
 * fallback provider.
 */
@Component
class SecurityContextActorProvider : ActorProvider {
    override fun currentActor(): String {
        val authentication = SecurityContextHolder.getContext().authentication
        return if (authentication == null ||
            !authentication.isAuthenticated ||
            authentication is AnonymousAuthenticationToken
        ) {
            SYSTEM_ACTOR
        } else {
            authentication.name
        }
    }

    private companion object {
        private const val SYSTEM_ACTOR = "system"
    }
}
