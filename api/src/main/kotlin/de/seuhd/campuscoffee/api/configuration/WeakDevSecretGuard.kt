package de.seuhd.campuscoffee.api.configuration

import jakarta.annotation.PostConstruct
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

/**
 * Fails startup fast in a public (`prod`) deployment if the committed dev-only fallback secrets are in
 * effect. The `dev` profile ships an insecure JWT signing secret and an RSA login key as committed fallbacks
 * so a local run needs no configuration, and the same key seeds the system tests. Those values are public
 * (they live in `application.yaml` and the git history), so a prod deployment that reuses them (a stray
 * `SPRING_PROFILES_ACTIVE=dev`, or copying a fallback into `JWT_SECRET` / `LOGIN_PRIVATE_KEY_PEM`) would let
 * anyone forge admin tokens or decrypt the login payload. This guard turns that misconfiguration into a
 * refused startup. It is `@Profile("prod")`, mirroring [PublicBaseUrlGuard]; local dev and the tests, which
 * legitimately use the fallback key under a non-prod profile, are unaffected.
 *
 * @property jwtProperties the resolved JWT signing secret, checked against the committed dev value.
 * @property loginEncryptionProperties the resolved login key, checked against the committed dev key.
 */
@Configuration
@Profile("prod")
class WeakDevSecretGuard(
    private val jwtProperties: JwtProperties,
    private val loginEncryptionProperties: LoginEncryptionProperties
) {
    /**
     * Rejects the committed dev JWT secret or the committed dev RSA login key once the context is built,
     * before serving traffic.
     */
    @PostConstruct
    fun rejectCommittedDevSecrets() {
        require(jwtProperties.secret != DEV_JWT_SECRET) {
            "campus-coffee.jwt.secret is the committed dev-only fallback, which is public; set a real " +
                "JWT_SECRET for a prod deployment."
        }
        val normalizedKey = loginEncryptionProperties.privateKeyPem.replace("\\n", "\n")
        require(!normalizedKey.contains(DEV_LOGIN_KEY_FRAGMENT)) {
            "campus-coffee.login-encryption.private-key-pem is the committed dev-only fallback key, which is " +
                "public; set a real LOGIN_PRIVATE_KEY_PEM for a prod deployment."
        }
    }

    private companion object {
        const val DEV_JWT_SECRET = "dev-only-insecure-jwt-secret-change-me-in-production"

        // A stable fragment of the committed dev RSA key body (the application.yaml dev fallback, which the
        // system-test key reuses); matching it identifies the public fallback key under either the
        // real-newline or the single-line literal-\n PEM encoding.
        const val DEV_LOGIN_KEY_FRAGMENT = "MIIEvAIBADANBgkqhkiG9w0BAQEFAASCBKYwggSiAgEAAoIBAQCMOOezSrpFsR9K"
    }
}
