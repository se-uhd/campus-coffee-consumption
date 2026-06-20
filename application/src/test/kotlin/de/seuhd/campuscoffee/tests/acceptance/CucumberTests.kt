package de.seuhd.campuscoffee.tests.acceptance

import io.cucumber.junit.platform.engine.Constants
import org.junit.platform.suite.api.ConfigurationParameter
import org.junit.platform.suite.api.IncludeEngines
import org.junit.platform.suite.api.SelectPackages
import org.junit.platform.suite.api.Suite

/**
 * Test runner for the Cucumber tests.
 */
@Suite
@IncludeEngines("cucumber")
@SelectPackages("de.seuhd.campuscoffee.tests.acceptance")
@ConfigurationParameter(
    key = Constants.PLUGIN_PROPERTY_NAME,
    value = "pretty, html:target/cucumber-report/cucumber.html"
)
@ConfigurationParameter(
    key = Constants.GLUE_PROPERTY_NAME,
    value = "de.seuhd.campuscoffee.tests.acceptance"
)
@ConfigurationParameter(
    key = Constants.FILTER_TAGS_PROPERTY_NAME,
    value = "not @inactive"
)
class CucumberTests
