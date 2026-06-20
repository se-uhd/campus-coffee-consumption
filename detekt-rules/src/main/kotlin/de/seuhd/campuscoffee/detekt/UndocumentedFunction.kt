package de.seuhd.campuscoffee.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtNamedFunction

/**
 * Reports every non-local, non-override named function that has no KDoc, regardless of visibility.
 * Local functions and overrides are exempt.
 */
class UndocumentedFunction(
    config: Config
) : Rule(config, "Non-local, non-override functions require KDoc.") {
    override fun visitNamedFunction(function: KtNamedFunction) {
        if (function.isLocal || function.hasModifier(KtTokens.OVERRIDE_KEYWORD)) return
        if (function.docComment != null) return
        report(
            Finding(
                Entity.atName(function),
                "The function ${function.nameAsSafeName} is missing KDoc."
            )
        )
    }
}
