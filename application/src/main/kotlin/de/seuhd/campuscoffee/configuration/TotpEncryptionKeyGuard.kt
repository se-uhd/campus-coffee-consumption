package de.seuhd.campuscoffee.configuration

import de.seuhd.campuscoffee.data.system.TotpProperties
import jakarta.annotation.PostConstruct
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

/**
 * Fails startup fast in a public (`prod`) deployment if the committed dev-only TOTP encryption key is in
 * effect. [TotpProperties] ships that key as a committed default so a local run and the system tests need no
 * configuration, but it is public (it lives in the source), so a prod deployment that kept it would let
 * anyone who reads the database decrypt every admin's second-factor secret. This guard turns that
 * misconfiguration into a refused startup, mirroring the api layer's `WeakDevSecretGuard` for the JWT and
 * login keys. It lives in the application module because [TotpProperties] is data-owned and the api module,
 * where the sibling guard sits, does not depend on data.
 *
 * @property totpProperties the resolved TOTP settings, checked against the committed dev key.
 */
@Configuration
@Profile("prod")
class TotpEncryptionKeyGuard(
    private val totpProperties: TotpProperties
) {
    /**
     * Rejects the committed dev TOTP encryption key, or a pinned dev TOTP clock, once the context is built and
     * before serving traffic. Both are dev-only conveniences that must never be in effect in prod.
     */
    @PostConstruct
    fun rejectCommittedDevKey() {
        require(totpProperties.encryptionKey != DEV_TOTP_KEY) {
            "campus-coffee.totp.encryption-key is the committed dev-only default, which is public; set a real " +
                "TOTP_ENCRYPTION_KEY for a prod deployment."
        }
        require(totpProperties.fixedClockEpochSecond <= 0) {
            "campus-coffee.totp.fixed-clock-epoch-second is set, which freezes the second-factor time base " +
                "to a fixed, predictable code; it is a local-dev convenience only and must be 0 in a prod " +
                "deployment."
        }
    }

    private companion object {
        // must match the default in TotpProperties.encryptionKey
        const val DEV_TOTP_KEY = "dev-only-totp-encryption-key-change-in-prod"
    }
}
