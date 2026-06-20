package de.seuhd.campuscoffee

import dev.detekt.gradle.Detekt
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jlleitschuh.gradle.ktlint.KtlintExtension

// Kotlin configuration for the modules: the Java toolchain and target, the Spring all-open plugin
// (so @Component/@Transactional classes can be proxied), ktlint formatting/linting, and the Kotlin
// reflection library that Spring requires at runtime. The Java major version is sourced from the
// version catalog (libs `java`), the same entry the Java toolchain uses, so the two cannot diverge.
plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    id("org.jlleitschuh.gradle.ktlint")
    id("dev.detekt")
}

val libs = the<VersionCatalogsExtension>().named("libs")
val javaVersion = libs.findVersion("java").get().requiredVersion

kotlin {
    jvmToolchain(javaVersion.toInt())
    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget(javaVersion))
        // retain parameter names for Spring's @PathVariable/@RequestParam binding, as -parameters does for Java
        javaParameters.set(true)
    }
}

// Formatting follows the official Kotlin style; the rules and line length come from the root
// .editorconfig. The plugin wires ktlintCheck into `check`, so the format gate rides on the build.
configure<KtlintExtension> {
    version.set(libs.findVersion("ktlint-tool").get().requiredVersion)
}

// Static analysis with detekt: the plugin wires its task into `check` and fails the build on findings by
// default. buildUponDefaultConfig layers our config on detekt's defaults; config/detekt/detekt.yml enables
// the custom campus-coffee-kdoc rules, and the per-module baseline grandfathers the current findings so
// only new ones break the build.
detekt {
    buildUponDefaultConfig = true
    parallel = true
    baseline = file("detekt-baseline.xml")
    config.from(rootProject.file("config/detekt/detekt.yml"))
}

// The check-wired `detekt` task scans main and test sources by default. The KDoc rules apply to
// production code only (test sources are exempt), so restrict it to the main source set.
tasks.named<Detekt>("detekt") {
    setSource(files("src/main/kotlin"))
}

dependencies {
    implementation(kotlin("reflect"))
    // The custom KDoc rules; activated in config/detekt/detekt.yml.
    detektPlugins(project(":detekt-rules"))
}
