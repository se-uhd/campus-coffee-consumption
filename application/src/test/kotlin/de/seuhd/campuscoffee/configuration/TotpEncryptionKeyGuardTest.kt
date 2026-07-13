package de.seuhd.campuscoffee.configuration

import de.seuhd.campuscoffee.data.system.TotpProperties
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Unit tests for the prod fail-fast guard: it refuses to start production with the committed dev TOTP
 * encryption key or a pinned dev clock, and accepts a real key with the real clock.
 */
class TotpEncryptionKeyGuardTest {
    // must match the committed default in TotpProperties.encryptionKey
    private val devKey = "dev-only-totp-encryption-key-change-in-prod"
    private val realKey = "a-real-prod-totp-key-supplied-by-secret-manager"

    private fun guard(
        encryptionKey: String,
        fixedClockEpochSecond: Long = 0
    ) = TotpEncryptionKeyGuard(
        TotpProperties(encryptionKey = encryptionKey, fixedClockEpochSecond = fixedClockEpochSecond)
    )

    @Test
    fun `rejects the committed dev encryption key`() {
        assertThatThrownBy { guard(encryptionKey = devKey).rejectCommittedDevKey() }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("TOTP_ENCRYPTION_KEY")
    }

    @Test
    fun `rejects a pinned dev clock even with a real encryption key`() {
        assertThatThrownBy {
            guard(encryptionKey = realKey, fixedClockEpochSecond = 1_735_732_800L).rejectCommittedDevKey()
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("fixed-clock-epoch-second")
    }

    @Test
    fun `accepts a real encryption key with the real clock`() {
        assertThatCode { guard(encryptionKey = realKey, fixedClockEpochSecond = 0).rejectCommittedDevKey() }
            .doesNotThrowAnyException()
    }
}
