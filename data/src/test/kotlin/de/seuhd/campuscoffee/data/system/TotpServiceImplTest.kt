package de.seuhd.campuscoffee.data.system

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Unit tests for the TOTP adapter: the RFC 6238 math against a published test vector, the encrypt/verify
 * round-trip, the percent-encoded otpauth URI, and the deterministic dev clock.
 */
class TotpServiceImplTest {
    // RFC 6238 Appendix B: the shared secret "12345678901234567890" (base32-encoded below); at Unix time 59s
    // the SHA-1 8-digit code is 94287082, so the 6-digit code is 287082, at time step 1 (59 / 30).
    private val rfcSecretBase32 = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"
    private val fixedClockAt59s: Clock = Clock.fixed(Instant.ofEpochSecond(59), ZoneOffset.UTC)

    private fun serviceAt(
        systemClock: Clock,
        fixedClockEpochSecond: Long = 0
    ): TotpServiceImpl = TotpServiceImpl(TotpProperties(fixedClockEpochSecond = fixedClockEpochSecond), systemClock)

    @Test
    fun `verify accepts the RFC 6238 test-vector code at its time step`() {
        val service = serviceAt(fixedClockAt59s)
        val verification = service.verify(service.encrypt(rfcSecretBase32), "287082")
        assertThat(verification).isNotNull
        assertThat(verification!!.matchedTimeStep).isEqualTo(1L)
    }

    @Test
    fun `verify returns null for a wrong code`() {
        val service = serviceAt(fixedClockAt59s)
        assertThat(service.verify(service.encrypt(rfcSecretBase32), "000000")).isNull()
    }

    @Test
    fun `verify accepts a freshly generated secret's own current code round-trip`() {
        val service = serviceAt(Clock.systemUTC())
        val enrollment = service.enroll("jane_doe")
        // reconstruct the current code from the enrolled secret and confirm the adapter accepts it
        val currentCode =
            com.bastiaanjansen.otp.TOTPGenerator
                .Builder(enrollment.base32Secret)
                .build()
                .now()
        assertThat(service.verify(enrollment.encryptedSecret, currentCode)).isNotNull
    }

    @Test
    fun `enroll builds an otpauth URI with a percent-encoded issuer and the secret`() {
        val enrollment = serviceAt(Clock.systemUTC()).enroll("jane_doe")
        assertThat(enrollment.otpauthUri)
            .startsWith("otpauth://totp/")
            // the issuer "SE@UHD" must be percent-encoded (the '@' becomes %40), or authenticator apps misparse it
            .contains("SE%40UHD")
            .contains("secret=${enrollment.base32Secret}")
    }

    @Test
    fun `a pinned dev clock makes the code a deterministic constant`() {
        // the real system clock is ignored when a fixed dev instant is configured, so the code is a constant.
        // At 2025-01-01T12:00:00Z (epoch 1735732800, step 57857760) the RFC secret's code is 217662, the value
        // the dev profile documents for the pre-enrolled fixture admin.
        val service = serviceAt(Clock.systemUTC(), fixedClockEpochSecond = 1_735_732_800L)
        val verification = service.verify(service.encrypt(rfcSecretBase32), "217662")
        assertThat(verification).isNotNull
        assertThat(verification!!.matchedTimeStep).isEqualTo(57_857_760L)
    }
}
