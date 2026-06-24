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
 * @property cookieName the name of the cookie carrying the JWT.
 */
class CookieOrHeaderBearerTokenResolver(
    private val cookieName: String
) : BearerTokenResolver {
    private val headerResolver = DefaultBearerTokenResolver()

    /**
     * Returns the bearer token from the `Authorization` header if present, else from the session cookie,
     * else null.
     *
     * @param request the current request.
     */
    override fun resolve(request: HttpServletRequest): String? =
        headerResolver.resolve(request) ?: request.cookies?.firstOrNull { it.name == cookieName }?.value
}
