package de.seuhd.campuscoffee.api.security

import de.seuhd.campuscoffee.api.configuration.AuthCookieProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.dao.DaoAuthenticationProvider
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.crypto.factory.PasswordEncoderFactories
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.access.AccessDeniedHandler

/**
 * Spring Security configuration. Two authentication mechanisms, one per audience (there is no HTTP Basic):
 * - **Admins** authenticate with a **JWT bearer token** (minted by the token endpoint from a
 *   username+password login), mapped by the resource server to a `ROLE_ADMIN` principal. The browser SPA
 *   carries the token in an httpOnly, `SameSite=Strict` session cookie; API clients and the tests carry it in
 *   the `Authorization` header. [CookieOrHeaderBearerTokenResolver] reads it from either.
 * - **Members** authenticate with their secret **capability token** (the `X-Capability-Token` header), resolved
 *   by [CapabilityTokenAuthenticationFilter] to a `ROLE_USER` principal; even an admin's own token grants
 *   only self-service.
 *
 * The filter chain is stateless (no server-side session). CSRF token protection is disabled because the only
 * cookie (the admin session) is `SameSite=Strict` (never sent cross-site, so it cannot be ridden by a forged
 * request) and the member and API flows authenticate with a custom header a cross-site page cannot set. A
 * Content-Security-Policy and the other security headers are set on the chain. The access rules gate the API
 * surface by audience; the finer ownership rules (a member acting only on their own count; a user editing
 * only their own account; the deactivated-member read-only rule) depend on the targeted resource, so they
 * live in the domain services. Non-`/api` GET routes serve the bundled Angular SPA (its `index.html` and
 * assets, plus deep links), so they are public; the SPA makes the authenticated API calls.
 */
@Configuration
class SecurityConfig {
    /**
     * Builds the stateless filter chain that enforces the access rules described in the class KDoc.
     *
     * @param http the security builder for the chain
     * @param authenticationEntryPoint renders a missing or invalid credential as a 401 JSON response
     * @param accessDeniedHandler renders an authorization denial as a 403 JSON response
     * @param jwtAuthenticationConverter maps a validated Bearer token to a principal with ROLE_* authorities
     * @param capabilityTokenAuthenticationFilter authenticates a member by their X-Capability-Token header
     * @param environment the active environment, used to open the dev data endpoints only under `dev`
     * @param authCookieProperties the admin session-cookie settings (the cookie name the bearer resolver reads)
     */
    @Bean
    fun securityFilterChain(
        http: HttpSecurity,
        authenticationEntryPoint: AuthenticationEntryPoint,
        accessDeniedHandler: AccessDeniedHandler,
        jwtAuthenticationConverter: JwtAuthenticationConverter,
        capabilityTokenAuthenticationFilter: CapabilityTokenAuthenticationFilter,
        environment: Environment,
        authCookieProperties: AuthCookieProperties
    ): SecurityFilterChain {
        http {
            authorizeHttpRequests {
                // The token endpoint, the logout endpoint, the login-encryption public key, and the API
                // docs stay reachable without an existing session.
                authorize("/api/auth/token", permitAll)
                authorize("/api/auth/logout", permitAll)
                authorize("/api/auth/public-key", permitAll)
                authorize("/api/swagger-ui.html", permitAll)
                authorize("/api/swagger-ui/**", permitAll)
                authorize("/api/api-docs/**", permitAll)
                // The dev data endpoints are opened only under the dev profile, never in a deployed profile;
                // the DevController is itself `@Profile("dev")`, so this couples the open rule to its scoping.
                if (environment.matchesProfiles("dev")) {
                    authorize("/api/dev/**", permitAll)
                }
                // Actuator: health is public (only UP/DOWN); every other actuator endpoint is admin-only.
                // The catch-all `/actuator/**` rule must precede the SPA GET catch-all below, which would
                // otherwise make any exposed endpoint (e.g. /actuator/env, /actuator/metrics) anonymous.
                authorize("/actuator/health", permitAll)
                authorize("/actuator/health/**", permitAll)
                authorize("/actuator/**", hasRole("ADMIN"))
                // Member self-service: the capability token principal (ROLE_USER). The domain enforces that
                // a member acts only on their own data and that a deactivated member is read-only. A member
                // sees the price and the kitty balance through their own /summary, never the admin reads.
                authorize("/api/consumption/**", hasRole("USER"))
                authorize("/api/profile/**", hasRole("USER"))
                authorize("/api/summary/**", hasRole("USER"))
                authorize("/api/activity/**", hasRole("USER"))
                authorize("/api/expenses/**", hasRole("USER"))
                // Admin-only money management (JWT, ROLE_ADMIN): the price and the kitty (its history, member
                // deposits, and adjustments all live under /api/kitty).
                authorize("/api/price", hasRole("ADMIN"))
                authorize("/api/price/**", hasRole("ADMIN"))
                authorize("/api/kitty/**", hasRole("ADMIN"))
                // Member management and the per-member admin views are admin-only (JWT, ROLE_ADMIN).
                authorize("/api/users/**", hasRole("ADMIN"))
                // No anonymous access to any other API endpoint.
                authorize("/api/**", authenticated)
                // Every non-API GET serves the SPA (index.html, assets, and client-side deep links).
                authorize(org.springframework.http.HttpMethod.GET, "/**", permitAll)
                // Any other (non-API, non-GET) request requires authentication.
                authorize(anyRequest, authenticated)
            }
            // CSRF token protection is unnecessary here: the admin JWT cookie is SameSite=Strict (so the
            // browser never sends it on a cross-site request, which is the CSRF vector), and the member and
            // API flows authenticate with a custom header (X-Capability-Token / Authorization), which a
            // cross-site page cannot set. A token-based CSRF scheme would instead force a token onto those
            // header-authenticated, CSRF-immune endpoints (and the system tests) for no added protection.
            csrf { disable() }
            sessionManagement { sessionCreationPolicy = SessionCreationPolicy.STATELESS }
            // Security response headers. The Content-Security-Policy is the structural XSS mitigation: the
            // SPA and all its resources are same-origin, so `default-src 'self'` plus `connect-src 'self'`
            // means even an injected script could not load from or exfiltrate to another origin; Angular
            // Material injects runtime <style> tags, so style-src allows inline styles. `frame-ancestors
            // 'none'` blocks clickjacking. Spring's defaults already add X-Content-Type-Options and
            // X-Frame-Options.
            headers {
                contentSecurityPolicy {
                    policyDirectives =
                        "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; " +
                        "img-src 'self' data: blob:; font-src 'self' data:; connect-src 'self'; " +
                        "object-src 'none'; base-uri 'self'; frame-ancestors 'none'"
                }
            }
            // Bearer-token (JWT) resource server: a valid token authenticates an admin request, its `roles`
            // claim mapped to ROLE_* authorities so the rules above apply. The token is read from the
            // SameSite httpOnly session cookie (the SPA) or the Authorization header (API clients, tests).
            oauth2ResourceServer {
                bearerTokenResolver = CookieOrHeaderBearerTokenResolver(authCookieProperties.name)
                jwt { this.jwtAuthenticationConverter = jwtAuthenticationConverter }
            }
            // Render auth failures as the application's JSON ErrorResponse: a missing or invalid credential
            // as 401, and an authenticated caller hitting a role-gated URL as 403.
            exceptionHandling {
                this.authenticationEntryPoint = authenticationEntryPoint
                this.accessDeniedHandler = accessDeniedHandler
            }
        }
        // run the capability token filter before the bearer-token filter so a member request is
        // authenticated as ROLE_USER before the resource server looks for a (missing) bearer token
        http.addFilterBefore(capabilityTokenAuthenticationFilter, BearerTokenAuthenticationFilter::class.java)
        return http.build()
    }

    /**
     * Maps a validated JWT to an authentication: the custom `roles` claim (bare role names) becomes
     * `ROLE_*` authorities and the principal name is the token subject (the login name).
     */
    @Bean
    fun jwtAuthenticationConverter(): JwtAuthenticationConverter {
        val converter = JwtAuthenticationConverter()
        converter.setJwtGrantedAuthoritiesConverter { jwt: Jwt ->
            val roles = jwt.getClaimAsStringList("roles") ?: emptyList()
            roles.map { SimpleGrantedAuthority("ROLE_$it") }
        }
        converter.setPrincipalClaimName("sub")
        return converter
    }

    /** Delegating encoder ({bcrypt} by default); shared with the data layer's hashing semantics. */
    @Bean
    fun passwordEncoder(): PasswordEncoder = PasswordEncoderFactories.createDelegatingPasswordEncoder()

    /**
     * Authenticates username/password against the [UserDetailsService] using the shared encoder. Retained
     * solely for the admin-login token endpoint (there is no HTTP Basic filter).
     *
     * @param userDetailsService loads the user record for the supplied login name
     * @param passwordEncoder verifies the supplied password against the stored hash
     */
    @Bean
    fun authenticationProvider(
        userDetailsService: UserDetailsService,
        passwordEncoder: PasswordEncoder
    ): DaoAuthenticationProvider {
        val provider = DaoAuthenticationProvider(userDetailsService)
        provider.setPasswordEncoder(passwordEncoder)
        return provider
    }

    /**
     * Exposes the [AuthenticationManager] so the token endpoint can reuse it to verify admin credentials.
     *
     * @param authenticationProvider the provider the manager delegates each authentication attempt to
     */
    @Bean
    fun authenticationManager(authenticationProvider: DaoAuthenticationProvider): AuthenticationManager =
        AuthenticationManager { authentication -> authenticationProvider.authenticate(authentication) }
}
