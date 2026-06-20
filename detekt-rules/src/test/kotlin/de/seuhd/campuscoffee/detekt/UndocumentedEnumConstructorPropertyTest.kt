package de.seuhd.campuscoffee.detekt

import dev.detekt.api.Config
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UndocumentedEnumConstructorPropertyTest {
    private val rule = UndocumentedEnumConstructorProperty(Config.empty)

    @Test
    fun `a non-private enum property missing @property is reported`() {
        val code =
            """
            /** Sizes. */
            enum class Size(val label: String) {
                SMALL("S"),
                LARGE("L")
            }
            """.trimIndent()
        assertEquals(1, rule.lintSource(code).size)
    }

    @Test
    fun `a documented enum property is not reported`() {
        val code =
            """
            /**
             * Sizes.
             * @property label the short label
             */
            enum class Size(val label: String) {
                SMALL("S"),
                LARGE("L")
            }
            """.trimIndent()
        assertTrue(rule.lintSource(code).isEmpty())
    }

    @Test
    fun `a private enum property is exempt`() {
        val code =
            """
            /** Sizes. */
            enum class Size(private val label: String) {
                SMALL("S"),
                LARGE("L")
            }
            """.trimIndent()
        assertTrue(rule.lintSource(code).isEmpty())
    }

    @Test
    fun `a param tag also satisfies the requirement`() {
        val code =
            """
            /**
             * Sizes.
             * @param label the short label
             */
            enum class Size(val label: String) {
                SMALL("S"),
                LARGE("L")
            }
            """.trimIndent()
        assertTrue(rule.lintSource(code).isEmpty())
    }
}
