package de.seuhd.campuscoffee.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtEnumEntry
import org.jetbrains.kotlin.psi.KtObjectDeclaration

/**
 * Reports every non-local class, interface, object, and enum class that has no KDoc. Local types
 * (declared inside a function body), enum entries, anonymous objects, and companion objects are exempt.
 */
class UndocumentedNonLocalClass(
    config: Config
) : Rule(config, "Non-local classes, interfaces, objects, and enums require KDoc.") {
    override fun visitClass(klass: KtClass) {
        reportIfUndocumented(klass)
        super.visitClass(klass)
    }

    override fun visitObjectDeclaration(declaration: KtObjectDeclaration) {
        // A companion object is part of its enclosing class, not a standalone type.
        if (!declaration.isCompanion()) {
            reportIfUndocumented(declaration)
        }
        super.visitObjectDeclaration(declaration)
    }

    /**
     * Reports [element] when it is a non-local class or object that has no KDoc. Enum entries and
     * anonymous objects are skipped.
     */
    private fun reportIfUndocumented(element: KtClassOrObject) {
        if (element is KtEnumEntry || element.isLocal || element.nameIdentifier == null) return
        if (element.docComment != null) return
        report(
            Finding(
                Entity.atName(element),
                "The ${element.nameAsSafeName} type is missing KDoc."
            )
        )
    }
}
