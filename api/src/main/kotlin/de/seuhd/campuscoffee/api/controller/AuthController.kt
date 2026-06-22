package de.seuhd.campuscoffee.api.controller

import de.seuhd.campuscoffee.api.dtos.TokenRequestDto
import de.seuhd.campuscoffee.api.dtos.TokenResponseDto
import io.github.oshai.kotlinlogging.KotlinLogging
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Authentication endpoint that exchanges credentials for a stateless JWT bearer token. The path is
 * relative to the resource; the central `/api` base is applied by ApiPathConfig.
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
    private val jwtEncoder: JwtEncoder
) {
    /**
     * Authenticates the credentials with the shared [AuthenticationManager] and returns a signed JWT
     * carrying the subject, the user's role, and a work-session expiry.
     *
     * @param request the login name and password to authenticate
     * @return 200 OK with the issued bearer token
     */
    @Operation(summary = "Authenticate with a login name and password and issue a JWT bearer token.")
    @PostMapping("/token")
    fun token(
        @RequestBody
        @Valid request: TokenRequestDto
    ): ResponseEntity<TokenResponseDto> {
        log.info { "Token requested for login name '${request.loginName}'." }
        // authenticate the credentials; wrong credentials raise an AuthenticationException that the
        // global exception handler renders as a JSON 401 (the endpoint never returns a token for them)
        val authentication =
            authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken(request.loginName, request.password)
            )

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
        return ResponseEntity.ok(TokenResponseDto(token))
    }

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
    }
}
