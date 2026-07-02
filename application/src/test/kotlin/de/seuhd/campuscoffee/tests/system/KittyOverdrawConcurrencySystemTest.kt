package de.seuhd.campuscoffee.tests.system

import de.seuhd.campuscoffee.domain.exceptions.ConflictException
import de.seuhd.campuscoffee.domain.model.ExpenseType
import de.seuhd.campuscoffee.domain.model.persistedId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * System test for the kitty overdraw guard under real concurrency, against a real Postgres (not a mock). It
 * seeds a kitty that holds exactly one full draw, then fires several kitty-funded expense draws at once
 * through the real services. The advisory lock serializes the read-modify-write, so exactly one draw fits
 * and the rest are refused with a [ConflictException], and the kitty never goes negative. Without the lock,
 * several draws could each read the same sufficient balance and overdraw the fund.
 */
class KittyOverdrawConcurrencySystemTest : AbstractSystemTest() {
    @Test
    fun `concurrent kitty draws on a single-draw kitty leave exactly one successful and the rest 409`() {
        val admin = seededUser("jane_doe")
        val buyer = seededUser("maxmustermann")
        // the kitty starts holding exactly one 100-cent draw
        paymentService.adjustKitty(amountCents = DRAW_CENTS, note = "float", actingUser = admin)

        val startGate = CountDownLatch(1)
        val done = CountDownLatch(DRAWS)
        val successes = AtomicInteger(0)
        val conflicts = AtomicInteger(0)
        val unexpected = AtomicInteger(0)
        val pool = Executors.newFixedThreadPool(DRAWS)
        repeat(DRAWS) {
            pool.submit {
                startGate.await()
                val outcome =
                    runCatching {
                        expenseService.record(
                            buyerUserId = buyer.persistedId,
                            expenseType = ExpenseType.BEANS,
                            beanName = "concurrency beans",
                            weightGrams = 100,
                            amountCents = DRAW_CENTS,
                            privateAmountCents = 0,
                            kittyAmountCents = DRAW_CENTS,
                            note = null,
                            actingUser = admin
                        )
                    }
                when {
                    outcome.isSuccess -> successes.incrementAndGet()
                    outcome.exceptionOrNull() is ConflictException -> conflicts.incrementAndGet()
                    else -> unexpected.incrementAndGet()
                }
                done.countDown()
            }
        }
        // release every thread at once to maximize contention on the kitty lock
        startGate.countDown()
        val finished = done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        pool.shutdownNow()

        assertThat(finished).isTrue()
        // no draw failed for any reason other than the overdraw guard, so the lock serialized the check
        assertThat(unexpected.get()).isEqualTo(0)
        // exactly one 100-cent draw fits a 100-cent kitty; the rest are refused, so the kitty never overdrew
        assertThat(successes.get()).isEqualTo(1)
        assertThat(conflicts.get()).isEqualTo(DRAWS - 1)
    }

    private companion object {
        private const val DRAWS = 8
        private const val DRAW_CENTS = 100
        private const val TIMEOUT_SECONDS = 30L
    }
}
