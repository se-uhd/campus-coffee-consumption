package de.seuhd.campuscoffee.detekt

import dev.detekt.api.RuleSet
import dev.detekt.api.RuleSetId
import dev.detekt.api.RuleSetProvider

/**
 * Registers the CampusCoffee KDoc rule set (`campus-coffee-kdoc`) with detekt. The rules are loaded
 * via the `detektPlugins(project(":detekt-rules"))` dependency and activated in `config/detekt/detekt.yml`.
 */
class KDocRuleSetProvider : RuleSetProvider {
    override val ruleSetId = RuleSetId("campus-coffee-kdoc")

    override fun instance(): RuleSet =
        RuleSet(
            ruleSetId,
            listOf(
                ::UndocumentedNonLocalClass,
                ::UndocumentedFunction,
                ::UndocumentedFunctionParameter,
                ::UndocumentedEnumConstructorProperty
            )
        )
}
