package de.seuhd.campuscoffee.detekt

import dev.detekt.api.Config
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UndocumentedNonLocalClassTest {
    private val rule = UndocumentedNonLocalClass(Config.empty)

    @Test
    fun `a class without KDoc is reported`() {
        assertEquals(1, rule.lintSource("class Widget").size)
    }

    @Test
    fun `a documented class is not reported`() {
        val code =
            """
            /** A widget. */
            class Widget
            """.trimIndent()
        assertTrue(rule.lintSource(code).isEmpty())
    }

    @Test
    fun `an enum class without KDoc is reported`() {
        assertEquals(1, rule.lintSource("enum class Color { RED, GREEN }").size)
    }

    @Test
    fun `enum entries do not each require KDoc`() {
        val code =
            """
            /** Colors. */
            enum class Color { RED, GREEN }
            """.trimIndent()
        assertTrue(rule.lintSource(code).isEmpty())
    }

    @Test
    fun `a companion object is exempt`() {
        val code =
            """
            /** A holder. */
            class Holder {
                companion object {
                    /** Makes one. */
                    fun create() = Holder()
                }
            }
            """.trimIndent()
        assertTrue(rule.lintSource(code).isEmpty())
    }

    @Test
    fun `a local class is exempt`() {
        val code =
            """
            /** Outer. */
            fun outer() {
                class Local
                Local()
            }
            """.trimIndent()
        assertTrue(rule.lintSource(code).isEmpty())
    }
}
