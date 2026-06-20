package de.seuhd.campuscoffee.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtClass

/**
 * Reports each non-private `val`/`var` parameter of an enum class's primary constructor that the
 * enum's KDoc does not document with `@property` or `@param`. Only enums that already have a KDoc are
 * checked; a wholly missing KDoc is reported by [UndocumentedNonLocalClass].
 */
class UndocumentedEnumConstructorProperty(
    config: Config
) : Rule(config, "Non-private enum constructor properties require @property or @param.") {
    override fun visitClass(klass: KtClass) {
        checkEnumProperties(klass)
        super.visitClass(klass)
    }

    /**
     * Reports each non-private `val`/`var` parameter of the enum [klass]'s primary constructor that its
     * KDoc does not document. Runs only when the enum has a KDoc.
     */
    private fun checkEnumProperties(klass: KtClass) {
        if (!klass.isEnum()) return
        val documented = klass.docComment?.documentedParameterNames() ?: return
        klass.primaryConstructor
            ?.valueParameters
            .orEmpty()
            .filter { it.hasValOrVar() && !it.hasModifier(KtTokens.PRIVATE_KEYWORD) }
            .filter { it.name != null && it.name !in documented }
            .forEach { parameter ->
                report(
                    Finding(
                        Entity.atName(parameter),
                        "Enum property '${parameter.nameAsSafeName}' is missing an @property or @param."
                    )
                )
            }
    }
}
