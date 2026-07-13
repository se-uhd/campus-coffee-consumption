package de.seuhd.campuscoffee.api.security

import de.seuhd.campuscoffee.api.configuration.TotpLoginProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Unit tests for the monotonic per-user code-reuse guard: it accepts only a strictly newer time step, so
 * neither the just-used step nor an earlier still-in-window step can be replayed.
 */
class TotpReplayGuardTest {
    private val user = "user-1"

    private fun guard(enabled: Boolean = true): TotpReplayGuard =
        TotpReplayGuard(TotpLoginProperties(stepReuseGuardEnabled = enabled))

    @Test
    fun `accepts a first step and then a strictly newer one`() {
        val guard = guard()
        assertThat(guard.accept(user, 100)).isTrue()
        assertThat(guard.accept(user, 101)).isTrue()
    }

    @Test
    fun `rejects reuse of the just-accepted step`() {
        val guard = guard()
        guard.accept(user, 100)
        assertThat(guard.accept(user, 100)).isFalse()
    }

    @Test
    fun `rejects an earlier still-in-window step after a newer one was accepted`() {
        val guard = guard()
        guard.accept(user, 100)
        // step 99 is still within the +/-1 drift band of a code verified at step 100, but it is not newer
        assertThat(guard.accept(user, 99)).isFalse()
    }

    @Test
    fun `tracks each user independently`() {
        val guard = guard()
        assertThat(guard.accept("a", 100)).isTrue()
        assertThat(guard.accept("b", 100)).isTrue()
    }

    @Test
    fun `accepts every step when the guard is disabled`() {
        val guard = guard(enabled = false)
        assertThat(guard.accept(user, 100)).isTrue()
        assertThat(guard.accept(user, 100)).isTrue()
    }
}
