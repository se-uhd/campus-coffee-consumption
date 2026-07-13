package de.seuhd.campuscoffee.data.system

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Configuration for the admin TOTP (two-factor) second factor, bound from `campus-coffee.totp.*`.
 *
 * The [encryptionKey] and [encryptionSalt] key the at-rest AES-256-GCM encryption of the TOTP secret. The
 * same key must be configured identically on every instance (a client may enroll on one instance and log in
 * on another). The default is a committed dev value; a `@Profile("prod")` guard rejects that default in
 * production, where the real key comes from Secret Manager. Only the key material is secret: the salt is a
 * fixed non-secret value (the per-encryption IV is random), and the [issuer] is a public label.
 *
 * [fixedClockEpochSecond] makes the second factor deterministic for local development, the same idea as
 * the seeded (deterministic) id generator: instead of a fake or bypassed code, it fixes the *time input* to
 * the real RFC 6238 computation to a constant instant, so the code for a given secret is a constant, known
 * value (the real algorithm runs unchanged; only the clock is mocked). Only the dev profile sets it; it is 0
 * (the real system clock) everywhere else, and a `@Profile("prod")` guard refuses to start prod with it set,
 * so it can never weaken a real deployment.
 *
 * @property encryptionKey the AES key material for the at-rest encryptor (dev fallback; overridden in prod)
 * @property encryptionSalt the hex-encoded salt for the at-rest encryptor
 * @property issuer the issuer label shown in the authenticator app
 * @property fixedClockEpochSecond a fixed Unix second to pin the TOTP clock to in local dev (0 = the real
 *   clock; must stay 0 in prod), making codes a deterministic constant
 */
@ConfigurationProperties("campus-coffee.totp")
data class TotpProperties(
    val encryptionKey: String = "dev-only-totp-encryption-key-change-in-prod",
    val encryptionSalt: String = "1a2b3c4d5e6f7081",
    val issuer: String = "SE@UHD",
    val fixedClockEpochSecond: Long = 0
)
