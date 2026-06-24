package de.seuhd.campuscoffee.api.configuration

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Configuration for the admin session cookie. The token endpoint sets the issued JWT as an httpOnly cookie
 * so the browser stores it where JavaScript cannot read it (an XSS cannot exfiltrate it), and the resource
 * server reads the bearer token from this cookie. The cookie is `SameSite=Strict` (so it is never sent on a
 * cross-site request, which is the CSRF defense) and, in any real deployment, `Secure`.
 *
 * @property name the cookie name carrying the admin JWT.
 * @property secure whether the cookie is marked `Secure` (https only). True by default; the dev profile,
 *   served over plain http on localhost, sets it false so the cookie is usable there.
 */
@ConfigurationProperties("campus-coffee.auth.cookie")
data class AuthCookieProperties(
    val name: String = "campus_coffee_admin",
    val secure: Boolean = true
)
