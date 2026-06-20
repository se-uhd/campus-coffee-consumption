package de.seuhd.campuscoffee.detekt

import dev.detekt.api.Config
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UndocumentedFunctionTest {
    private val rule = UndocumentedFunction(Config.empty)

    @Test
    fun `a non-local function without KDoc is reported`() {
        assertEquals(1, rule.lintSource("fun answer() = 42").size)
    }

    @Test
    fun `a documented function is not reported`() {
        val code =
            """
            /** Returns the answer. */
            fun answer() = 42
            """.trimIndent()
        assertTrue(rule.lintSource(code).isEmpty())
    }

    @Test
    fun `an override function is exempt`() {
        val code =
            """
            /** A base. */
            interface Base {
                /** Runs it. */
                fun run()
            }

            /** An implementation. */
            class Impl : Base {
                override fun run() = Unit
            }
            """.trimIndent()
        assertTrue(rule.lintSource(code).isEmpty())
    }

    @Test
    fun `a local function is exempt`() {
        val code =
            """
            /** Outer. */
            fun outer() {
                fun local() = 1
                local()
            }
            """.trimIndent()
        assertTrue(rule.lintSource(code).isEmpty())
    }

    @Test
    fun `a private function still requires KDoc`() {
        val code =
            """
            /** A holder. */
            class Holder {
                private fun secret() = 1
            }
            """.trimIndent()
        assertEquals(1, rule.lintSource(code).size)
    }
}
