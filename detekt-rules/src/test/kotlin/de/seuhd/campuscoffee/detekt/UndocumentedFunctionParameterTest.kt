package de.seuhd.campuscoffee.detekt

import dev.detekt.api.Config
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UndocumentedFunctionParameterTest {
    private val rule = UndocumentedFunctionParameter(Config.empty)

    @Test
    fun `a public function missing @param tags reports each parameter`() {
        val code =
            """
            /** Adds two numbers. */
            fun add(a: Int, b: Int) = a + b
            """.trimIndent()
        assertEquals(2, rule.lintSource(code).size)
    }

    @Test
    fun `a fully documented function is not reported`() {
        val code =
            """
            /**
             * Adds two numbers.
             * @param a the first addend
             * @param b the second addend
             */
            fun add(a: Int, b: Int) = a + b
            """.trimIndent()
        assertTrue(rule.lintSource(code).isEmpty())
    }

    @Test
    fun `a function without any KDoc is left to the KDoc-presence rule`() {
        assertTrue(rule.lintSource("fun add(a: Int, b: Int) = a + b").isEmpty())
    }

    @Test
    fun `a non-public function is exempt`() {
        val code =
            """
            /** A holder. */
            class Holder {
                /** Adds. */
                private fun add(a: Int, b: Int) = a + b
            }
            """.trimIndent()
        assertTrue(rule.lintSource(code).isEmpty())
    }
}
