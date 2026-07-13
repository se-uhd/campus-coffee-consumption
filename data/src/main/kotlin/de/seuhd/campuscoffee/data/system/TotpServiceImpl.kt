package de.seuhd.campuscoffee.data.system

import com.bastiaanjansen.otp.SecretGenerator
import com.bastiaanjansen.otp.TOTPGenerator
import de.seuhd.campuscoffee.domain.model.TotpEnrollment
import de.seuhd.campuscoffee.domain.model.TotpVerification
import de.seuhd.campuscoffee.domain.ports.system.TotpService
import org.springframework.security.crypto.encrypt.BytesEncryptor
import org.springframework.security.crypto.encrypt.Encryptors
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64

/**
 * Data-layer adapter for [TotpService]. Uses the otp-java library for the RFC 6238 math (SHA-1, 6 digits, a
 * 30-second period) and Spring Security's AES-256-GCM [Encryptors.stronger] for at-rest encryption of the
 * secret, so neither the TOTP library nor the crypto primitives leak into the domain or the web layer. The
 * raw base32 secret exists only inside this class, during enrollment and transiently during verification;
 * every caller holds and stores only the encrypted form.
 */
@Component
class TotpServiceImpl(
    private val properties: TotpProperties,
    systemClock: Clock
) : TotpService {
    // the real system clock, except in local dev where a configured fixed instant pins the time base so codes
    // are a deterministic constant (see TotpProperties.fixedClockEpochSecond); 0 uses the real clock
    private val clock: Clock =
        if (properties.fixedClockEpochSecond > 0) {
            Clock.fixed(Instant.ofEpochSecond(properties.fixedClockEpochSecond), ZoneOffset.UTC)
        } else {
            systemClock
        }
    private val encryptor: BytesEncryptor =
        Encryptors.stronger(properties.encryptionKey, properties.encryptionSalt)

    override fun enroll(accountName: String): TotpEnrollment {
        // otp-java's SecretGenerator returns the base32-encoded secret as ASCII bytes
        val base32Secret = String(SecretGenerator.generate(SECRET_BITS), StandardCharsets.UTF_8)
        return TotpEnrollment(
            encryptedSecret = encrypt(base32Secret),
            otpauthUri = otpauthUriFor(base32Secret, accountName),
            base32Secret = base32Secret
        )
    }

    override fun otpauthUri(
        encryptedSecret: String,
        accountName: String
    ): String = otpauthUriFor(decrypt(encryptedSecret), accountName)

    override fun encrypt(base32Secret: String): String =
        Base64.getEncoder().encodeToString(
            encryptor.encrypt(base32Secret.toByteArray(StandardCharsets.UTF_8))
        )

    override fun verify(
        encryptedSecret: String,
        code: String
    ): TotpVerification? {
        val currentStep = clock.millis() / (PERIOD_SECONDS * MILLIS_PER_SECOND)
        val generator = generatorFor(decrypt(encryptedSecret))
        // walk the +/-1 drift window and return the matched step, so the login path can reject reuse of a
        // code within its window (at(step * period) generates the code for that exact time step). The lower
        // bound is clamped to step 1 because the library rejects a non-positive time; only reachable within
        // the first 30 seconds of the Unix epoch (never in production), so no real drift tolerance is lost.
        return (maxOf(1L, currentStep - DRIFT_STEPS)..(currentStep + DRIFT_STEPS))
            .firstOrNull { step -> constantTimeEquals(generator.at(step * PERIOD_SECONDS), code) }
            ?.let { TotpVerification(it) }
    }

    /** Decrypts the at-rest ciphertext back to the raw base32 secret (transiently, inside this adapter). */
    private fun decrypt(encryptedSecret: String): String =
        String(encryptor.decrypt(Base64.getDecoder().decode(encryptedSecret)), StandardCharsets.UTF_8)

    /** Builds and percent-encodes the `otpauth://` URI (issuer, account, secret, SHA-1/6-digit/30-second). */
    private fun otpauthUriFor(
        base32Secret: String,
        accountName: String
    ): String = generatorFor(base32Secret).getURI(properties.issuer, accountName).toString()

    /** A TOTP generator over the given base32 secret with the RFC 6238 defaults (SHA-1, 6 digits, 30 seconds). */
    private fun generatorFor(base32Secret: String): TOTPGenerator = TOTPGenerator.Builder(base32Secret).build()

    /** Compares two codes in constant time, so a wrong code cannot be narrowed down by response timing. */
    private fun constantTimeEquals(
        expected: String,
        candidate: String
    ): Boolean =
        MessageDigest.isEqual(
            expected.toByteArray(StandardCharsets.UTF_8),
            candidate.toByteArray(StandardCharsets.UTF_8)
        )

    private companion object {
        const val SECRET_BITS = 160
        const val PERIOD_SECONDS = 30L
        const val MILLIS_PER_SECOND = 1000L
        const val DRIFT_STEPS = 1L
    }
}
