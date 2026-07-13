package de.seuhd.campuscoffee.api.support

import de.seuhd.campuscoffee.api.configuration.AuthCookieProperties
import org.springframework.http.ResponseCookie
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Mints the admin session: the signed JWT and the httpOnly, `SameSite=Strict` session cookie that carries
 * it. Shared by the login endpoint (which issues a full or enrollment-only session) and the two-factor
 * activate endpoint (which upgrades an enrollment-only session to a full one). There is no refresh flow:
 * this is an internal, few-admin tool, so the token gets a moderate TTL and the SPA re-authenticates on
 * expiry.
 */
@Component
class AdminSessionFactory(
    private val jwtEncoder: JwtEncoder,
    private val cookieProperties: AuthCookieProperties
) {
    /**
     * Mints a signed JWT for [loginName] carrying the given [roles] (bare role names, e.g. `["ADMIN"]`),
     * with a work-session expiry.
     *
     * @param loginName the token subject (the admin's login name)
     * @param roles the bare role names for the `roles` claim
     * @return the compact-serialized JWT
     */
    fun mintToken(
        loginName: String,
        roles: List<String>
    ): String {
        val now = Instant.now()
        val claims =
            JwtClaimsSet
                .builder()
                .subject(loginName)
                // a `roles` claim carrying the bare role names; the resource server's converter maps these
                // back to ROLE_* authorities. It stays a list even for a single role.
                .claim("roles", roles)
                .issuedAt(now)
                .expiresAt(now.plus(TOKEN_TTL_HOURS, ChronoUnit.HOURS))
                .build()
        // the signing key is a symmetric HMAC secret, so the header must name an HMAC algorithm for the
        // encoder to select that key
        val header = JwsHeader.with(MacAlgorithm.HS256).build()
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).tokenValue
    }

    /**
     * The session cookie carrying [token]: httpOnly and SameSite=Strict always, Secure in any real deployment
     * (see [AuthCookieProperties.secure]), scoped to the whole site, with the work-session lifetime.
     *
     * @param token the JWT to carry
     * @return the Set-Cookie value
     */
    fun sessionCookie(token: String): ResponseCookie = cookie(token, Duration.ofHours(TOKEN_TTL_HOURS))

    /** The Set-Cookie value that expires the session cookie (logout). */
    fun clearCookie(): ResponseCookie = cookie("", Duration.ZERO)

    /** Builds the session cookie carrying [value] with the given [maxAge] (zero expires it). */
    private fun cookie(
        value: String,
        maxAge: Duration
    ): ResponseCookie =
        ResponseCookie
            .from(cookieProperties.name, value)
            .httpOnly(true)
            .secure(cookieProperties.secure)
            .sameSite("Strict")
            .path("/")
            .maxAge(maxAge)
            .build()

    private companion object {
        private const val TOKEN_TTL_HOURS = 10L
    }
}
