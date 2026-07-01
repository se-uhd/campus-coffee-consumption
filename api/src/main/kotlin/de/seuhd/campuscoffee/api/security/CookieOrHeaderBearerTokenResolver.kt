package de.seuhd.campuscoffee.api.security

import jakarta.servlet.http.HttpServletRequest
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver

/**
 * Resolves the admin bearer token from either the `Authorization: Bearer` header or the session cookie.
 *
 * The SPA authenticates with the httpOnly session cookie (which JavaScript cannot read, so an XSS cannot
 * steal it); programmatic API clients and the system tests authenticate with the standard `Authorization`
 * header. The header takes precedence when both are present; otherwise the cookie value is used. This lets
 * the cookie-based browser flow and the header-based API flow coexist on one resource server.
 *
 * A request that explicitly presents a user capability token (the
 * [CapabilityTokenAuthenticationFilter.CAPABILITY_TOKEN_HEADER] header) is acting as that user, so the
 * ambient admin session cookie (which the browser sends automatically on every same-site request) is
 * not resolved as a bearer token and cannot override the caller's explicit user credential. Without
 * this, an admin who opened a user's capability URL in their own logged-in browser would send both, and the
 * cookie's JWT (authenticated by the resource server after the capability filter) would attribute the user's
 * action to the admin. An explicit `Authorization` header is still honored, since that is itself a
 * deliberate admin credential rather than an ambient cookie.
 *
 * @property cookieName the name of the cookie carrying the JWT.
 */
class CookieOrHeaderBearerTokenResolver(
    private val cookieName: String
) : BearerTokenResolver {
    private val headerResolver = DefaultBearerTokenResolver()

    /**
     * Returns the bearer token from the `Authorization` header if present; otherwise the session cookie,
     * unless the request presents a capability token (in which case the cookie is ignored); otherwise null.
     *
     * @param request the current request.
     */
    override fun resolve(request: HttpServletRequest): String? {
        headerResolver.resolve(request)?.let { return it }
        val capabilityToken = request.getHeader(CapabilityTokenAuthenticationFilter.CAPABILITY_TOKEN_HEADER)
        // a request presenting a capability token is acting as that user, so ignore the ambient session cookie
        return if (!capabilityToken.isNullOrBlank()) {
            null
        } else {
            request.cookies?.firstOrNull { it.name == cookieName }?.value
        }
    }
}
