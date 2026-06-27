package de.seuhd.campuscoffee

import de.seuhd.campuscoffee.domain.ports.system.StartupTaskService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.Test

/**
 * Unit tests for the startup orchestrator: it must run the registered tasks in ascending order (so the
 * event-log import precedes the rebuild, which precedes the fixture load) regardless of the order Spring
 * injects them, and tolerate an empty list (no task whose property is set).
 */
class StartupDataInitializerTest {
    @Test
    fun `runs the startup tasks in ascending order regardless of injection order`() {
        val sequence = mutableListOf<Int>()

        fun task(taskOrder: Int) =
            object : StartupTaskService {
                override val order = taskOrder

                override fun run() {
                    sequence.add(taskOrder)
                }
            }

        StartupDataInitializer(listOf(task(200), task(0), task(100))).afterSingletonsInstantiated()

        assertThat(sequence).containsExactly(0, 100, 200)
    }

    @Test
    fun `does nothing when no startup tasks are registered`() {
        assertThatCode {
            StartupDataInitializer(emptyList()).afterSingletonsInstantiated()
        }.doesNotThrowAnyException()
    }
}
