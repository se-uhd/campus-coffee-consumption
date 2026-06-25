package de.seuhd.campuscoffee.api.controller

import de.seuhd.campuscoffee.api.configuration.AuthCookieProperties
import de.seuhd.campuscoffee.api.dtos.PublicKeyDto
import de.seuhd.campuscoffee.api.dtos.TokenRequestDto
import de.seuhd.campuscoffee.api.dtos.TokenResponseDto
import de.seuhd.campuscoffee.api.exceptions.LoginPayloadException
import de.seuhd.campuscoffee.api.security.LoginAttemptLimiter
import de.seuhd.campuscoffee.api.security.LoginPayloadDecryptor
import io.github.oshai.kotlinlogging.KotlinLogging
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseCookie
import org.springframework.http.ResponseEntity
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.core.AuthenticationException
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Authentication endpoint that exchanges credentials for a stateless JWT bearer token. The path is
 * relative to the resource; the central `/api` base is applied by ApiWebConfig.
 *
 * Like registration, this endpoint must work without an existing token (the security chain leaves it
 * open). It authenticates the credentials with the shared [AuthenticationManager] and returns a signed
 * JWT carrying the subject (login name), the user's role, and a work-session expiry. The resource-server
 * filter then trusts an incoming token by its signature and expiry alone. There is deliberately no
 * refresh-token flow: this is an internal, few-admin tool, so the access token gets a moderate TTL and
 * the SPA redirects to login on expiry.
 */
@Tag(name = "Authentication", description = "Exchange credentials for a stateless JWT bearer token.")
@Controller
@RequestMapping("/auth")
class AuthController(
    private val authenticationManager: AuthenticationManager,
    private val jwtEncoder: JwtEncoder,
    private val loginPayloadDecryptor: LoginPayloadDecryptor,
    private val loginPublicKey: PublicKeyDto,
    private val cookieProperties: AuthCookieProperties,
    private val loginAttemptLimiter: LoginAttemptLimiter
) {
    /**
     * Returns the RSA public key (as a JWK) the client uses to encrypt the login payload. Public material
     * only; published openly so the SPA can fetch it before logging in.
     *
     * @return 200 OK with the public key JWK
     */
    @Operation(summary = "The RSA public key (JWK) used to encrypt the login payload.")
    @GetMapping("/public-key")
    fun publicKey(): ResponseEntity<PublicKeyDto> = ResponseEntity.ok(loginPublicKey)

    /**
     * Decrypts the credentials, authenticates them with the shared [AuthenticationManager], and returns a
     * signed JWT carrying the subject, the user's role, and a work-session expiry.
     *
     * Brute-force / DoS protected: a client (keyed on its IP) that exceeds the configured failure budget is
     * refused with 429 before any decrypt or bcrypt work runs (see [LoginAttemptLimiter]). A malformed
     * payload and a wrong password both count as a failure; a successful login resets the client's count.
     *
     * @param request the encrypted credentials (a compact JWE) to authenticate
     * @param httpRequest the servlet request, used to derive the rate-limit client key (its IP)
     * @return 200 OK with the issued bearer token
     */
    @Operation(summary = "Authenticate with an encrypted login payload and issue a JWT bearer token.")
    @PostMapping("/token")
    fun token(
        @RequestBody
        @Valid request: TokenRequestDto,
        httpRequest: HttpServletRequest
    ): ResponseEntity<TokenResponseDto> {
        // do not log the supplied login name: it is PII, and the canonical identifier (the user id) is not
        // resolved at the credential boundary (a failed attempt has no user). Logging the bare event is enough.
        log.info { "Token requested." }
        val clientKey = rateLimitClientKey(httpRequest)
        // refuse a client that has used up its failure budget before doing any decrypt/bcrypt work (429)
        loginAttemptLimiter.ensureWithinLimit(clientKey)
        // decrypt the payload (a malformed/undecryptable one raises a LoginPayloadException -> 400), then
        // authenticate; wrong credentials raise an AuthenticationException that the global exception handler
        // renders as a JSON 401 (the endpoint never returns a token for them). Either failure counts against
        // the client's rate-limit budget; a success clears it.
        val authentication =
            try {
                val credentials = loginPayloadDecryptor.decrypt(request.encryptedPayload.orEmpty())
                authenticationManager
                    .authenticate(UsernamePasswordAuthenticationToken(credentials.loginName, credentials.password))
                    .also { loginAttemptLimiter.recordSuccess(clientKey) }
            } catch (e: LoginPayloadException) {
                loginAttemptLimiter.recordFailure(clientKey)
                throw e
            } catch (e: AuthenticationException) {
                loginAttemptLimiter.recordFailure(clientKey)
                throw e
            }

        val now = Instant.now()
        val claims =
            JwtClaimsSet
                .builder()
                .subject(authentication.name)
                // a `roles` claim carrying the bare role names (e.g. ["ADMIN"]); the resource server's
                // converter maps these back to ROLE_* authorities. It stays a list even for a single role.
                .claim("roles", rolesOf(authentication))
                .issuedAt(now)
                .expiresAt(now.plus(TOKEN_TTL_HOURS, ChronoUnit.HOURS))
                .build()
        // the signing key is a symmetric HMAC secret, so the header must name an HMAC algorithm for the
        // encoder to select that key
        val header = JwsHeader.with(MacAlgorithm.HS256).build()
        val token = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).tokenValue
        // set the JWT as an httpOnly, SameSite=Strict cookie so the browser stores it where JavaScript
        // cannot read it (an XSS cannot exfiltrate it) and sends it automatically; the token is also returned
        // in the body for non-browser API clients (and the system tests), which authenticate with the header
        return ResponseEntity
            .ok()
            .header(HttpHeaders.SET_COOKIE, sessionCookie(token, Duration.ofHours(TOKEN_TTL_HOURS)).toString())
            .body(TokenResponseDto(token))
    }

    /**
     * Clears the admin session cookie. Idempotent and open (clearing one's own cookie needs no auth); the
     * SPA calls it on logout so the httpOnly cookie, which it cannot clear itself, is removed.
     *
     * @return 204 No Content with a Set-Cookie that expires the session cookie.
     */
    @Operation(summary = "Clear the admin session cookie.")
    @PostMapping("/logout")
    fun logout(): ResponseEntity<Void> =
        ResponseEntity
            .noContent()
            .header(HttpHeaders.SET_COOKIE, sessionCookie("", Duration.ZERO).toString())
            .build()

    /**
     * Builds the admin session cookie carrying the JWT: httpOnly and SameSite=Strict always, Secure in any
     * real deployment (see [AuthCookieProperties.secure]), scoped to the whole site. A zero [maxAge] expires
     * it (logout).
     *
     * @param value the JWT to carry (empty to clear the cookie).
     * @param maxAge the cookie lifetime (zero to expire it immediately).
     */
    private fun sessionCookie(
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

    /**
     * The per-client key the login rate limiter counts against: the originating client IP. Behind a proxy
     * (Cloud Run) the real client is the first hop of `X-Forwarded-For`; otherwise the direct socket address.
     *
     * @param httpRequest the servlet request to read the client address from.
     */
    private fun rateLimitClientKey(httpRequest: HttpServletRequest): String =
        httpRequest
            .getHeader(FORWARDED_FOR_HEADER)
            ?.substringBefore(',')
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: httpRequest.remoteAddr
            ?: "unknown"

    /** Strips the `ROLE_` prefix from the granted authorities to produce the bare role names. */
    private fun rolesOf(authentication: Authentication): List<String> =
        authentication.authorities
            .mapNotNull { it.authority }
            .filter { it.startsWith(ROLE_PREFIX) }
            .map { it.removePrefix(ROLE_PREFIX) }

    private companion object {
        private val log = KotlinLogging.logger {}
        private const val TOKEN_TTL_HOURS = 10L
        private const val ROLE_PREFIX = "ROLE_"
        private const val FORWARDED_FOR_HEADER = "X-Forwarded-For"
    }
}
