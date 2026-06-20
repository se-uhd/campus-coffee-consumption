package de.seuhd.campuscoffee.detekt

import dev.detekt.api.Finding
import dev.detekt.api.Rule
import dev.detekt.test.utils.compileContentForTest
import org.jetbrains.kotlin.config.LanguageVersionSettingsImpl

/**
 * Runs this rule over a Kotlin snippet and returns the findings it reports.
 *
 * @param code the Kotlin source to analyze
 * @return the findings the rule reported for [code]
 */
internal fun Rule.lintSource(code: String): List<Finding> =
    visitFile(compileContentForTest(code), LanguageVersionSettingsImpl.DEFAULT)
