package de.seuhd.campuscoffee.api.controller

import de.seuhd.campuscoffee.api.dtos.PublicKeyDto
import de.seuhd.campuscoffee.api.dtos.TokenRequestDto
import de.seuhd.campuscoffee.api.dtos.TokenResponseDto
import de.seuhd.campuscoffee.api.exceptions.LoginPayloadException
import de.seuhd.campuscoffee.api.security.ClientIpResolver
import de.seuhd.campuscoffee.api.security.EnrollmentState
import de.seuhd.campuscoffee.api.security.LoginAttemptLimiter
import de.seuhd.campuscoffee.api.security.LoginPayloadDecryptor
import de.seuhd.campuscoffee.api.support.AdminSessionFactory
import de.seuhd.campuscoffee.api.support.TotpLoginVerifier
import io.github.oshai.kotlinlogging.KotlinLogging
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.core.AuthenticationException
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping

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
    private val loginPayloadDecryptor: LoginPayloadDecryptor,
    private val loginPublicKey: PublicKeyDto,
    private val loginAttemptLimiter: LoginAttemptLimiter,
    private val clientIpResolver: ClientIpResolver,
    private val totpLoginVerifier: TotpLoginVerifier,
    private val adminSessionFactory: AdminSessionFactory
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
        val clientKey = clientIpResolver.clientIp(httpRequest)
        // refuse a client that has used up its failure budget before doing any decrypt/bcrypt work (429)
        loginAttemptLimiter.ensureWithinLimit(clientKey)
        // decrypt the payload (a malformed/undecryptable one raises a LoginPayloadException -> 400), then
        // authenticate the password, then verify the second factor. A wrong password or a wrong/missing
        // authenticator code both raise an AuthenticationException that the global exception handler renders
        // as the identical JSON 401, so neither reveals whether the account exists, is enrolled, or had the
        // right password. Either failure counts against the client's rate-limit budget; a success (both
        // factors) clears it. recordSuccess sits after the TOTP step so a wrong code is a recorded failure.
        val outcome =
            try {
                val credentials = loginPayloadDecryptor.decrypt(request.encryptedPayload.orEmpty())
                val authentication =
                    authenticationManager
                        .authenticate(UsernamePasswordAuthenticationToken(credentials.loginName, credentials.password))
                val enrollment = totpLoginVerifier.verifyForLogin(credentials.loginName, credentials.totp)
                loginAttemptLimiter.recordSuccess(clientKey)
                authentication to enrollment
            } catch (e: LoginPayloadException) {
                loginAttemptLimiter.recordFailure(clientKey)
                throw e
            } catch (e: AuthenticationException) {
                loginAttemptLimiter.recordFailure(clientKey)
                throw e
            }
        val (authentication, enrollment) = outcome

        // choose the token scope from the enrollment state: an enrolled admin gets a full ADMIN token; a
        // not-yet-enrolled admin gets an enrollment-only token (ADMIN_ENROLLMENT) that reaches only the
        // enrollment endpoints until they activate a second factor.
        val roles =
            when (enrollment) {
                EnrollmentState.ENROLLED -> rolesOf(authentication)
                EnrollmentState.PENDING -> listOf(ADMIN_ENROLLMENT_ROLE)
            }
        val token = adminSessionFactory.mintToken(authentication.name, roles)
        // set the JWT as an httpOnly, SameSite=Strict cookie so the browser stores it where JavaScript
        // cannot read it (an XSS cannot exfiltrate it) and sends it automatically; the token is also returned
        // in the body for non-browser API clients (and the system tests), which authenticate with the header.
        // `enrollmentRequired` tells the SPA to route a pending admin to the enrollment page.
        return ResponseEntity
            .ok()
            .header(HttpHeaders.SET_COOKIE, adminSessionFactory.sessionCookie(token).toString())
            .body(TokenResponseDto(token, enrollmentRequired = enrollment == EnrollmentState.PENDING))
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
            .header(HttpHeaders.SET_COOKIE, adminSessionFactory.clearCookie().toString())
            .build()

    /** Strips the `ROLE_` prefix from the granted authorities to produce the bare role names. */
    private fun rolesOf(authentication: Authentication): List<String> =
        authentication.authorities
            .mapNotNull { it.authority }
            .filter { it.startsWith(ROLE_PREFIX) }
            .map { it.removePrefix(ROLE_PREFIX) }

    private companion object {
        private val log = KotlinLogging.logger {}
        private const val ROLE_PREFIX = "ROLE_"
        private const val ADMIN_ENROLLMENT_ROLE = "ADMIN_ENROLLMENT"
    }
}
