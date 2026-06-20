package de.seuhd.campuscoffee

import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.tasks.testing.Test
import org.gradle.testing.jacoco.tasks.JacocoReport

// Applies JaCoCo so each module records execution data (build/jacoco/test.exec). The combined
// report and the coverage gate live in the :coverage subproject (jacoco-report-aggregation).
plugins {
    jacoco
}

val libs = the<VersionCatalogsExtension>().named("libs")

jacoco {
    toolVersion = libs.findVersion("jacoco").get().requiredVersion
}

// Render each module's own report (build/reports/jacoco/test/html) alongside the execution data, so
// the per-module reports the README documents actually exist after `gradle build`.
tasks.withType<Test>().configureEach {
    finalizedBy(tasks.withType<JacocoReport>())
}
