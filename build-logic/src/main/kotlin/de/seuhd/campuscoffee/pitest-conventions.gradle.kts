package de.seuhd.campuscoffee

import info.solidsoft.gradle.pitest.PitestPluginExtension
import org.gradle.api.artifacts.VersionCatalogsExtension

// Opt-in, local mutation testing (run with `-Pmutation`, e.g., `gradle :domain:pitest -Pmutation`).
// Not run by `build` or CI. Shared config lives here; each module sets its own targetClasses.
plugins {
    id("info.solidsoft.pitest")
}

val libs = the<VersionCatalogsExtension>().named("libs")

configure<PitestPluginExtension> {
    pitestVersion.set(libs.findVersion("pitest-tool").get().requiredVersion)
    junit5PluginVersion.set(libs.findVersion("pitest-junit5").get().requiredVersion)
    targetTests.set(listOf("de.seuhd.campuscoffee.*"))
    excludedClasses.set(
        listOf(
            "de.seuhd.campuscoffee.domain.tests.*",
            // glob covers the Kotlin file class (ApplicationKt) and companions
            "de.seuhd.campuscoffee.Application*",
            "de.seuhd.campuscoffee.LoadInitialData*",
            "de.seuhd.campuscoffee.*.*MapperImpl",
        )
    )
    mutators.set(listOf(providers.gradleProperty("pitest.mutators").orElse("DEFAULTS").get()))
    outputFormats.set(listOf("HTML", "XML"))
    timestampedReports.set(false)
    failWhenNoMutations.set(false)
    threads.set(1)
    timeoutConstInMillis.set(30000)
    jvmArgs.set(listOf("-XX:+EnableDynamicAgentLoading", "-Xshare:off"))
}
