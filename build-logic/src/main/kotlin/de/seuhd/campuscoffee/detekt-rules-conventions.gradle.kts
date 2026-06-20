package de.seuhd.campuscoffee

import dev.detekt.gradle.Detekt
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jlleitschuh.gradle.ktlint.KtlintExtension

// Build configuration for the :detekt-rules tooling module, which holds the custom detekt rules that
// kotlin-conventions loads via detektPlugins. It uses its own convention (not kotlin-conventions) only
// to avoid a self-referential detektPlugins(project(":detekt-rules")) dependency. It still dogfoods the
// rules: detekt loads them from this module's own compiled output, so the KDoc rules apply to the rule
// sources too. Test sources are exempt, matching the main modules.
plugins {
    kotlin("jvm")
    id("org.jlleitschuh.gradle.ktlint")
    id("dev.detekt")
}

val libs = the<VersionCatalogsExtension>().named("libs")
val javaVersion = libs.findVersion("java").get().requiredVersion

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(javaVersion.toInt())
    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget(javaVersion))
    }
}

configure<KtlintExtension> {
    version.set(libs.findVersion("ktlint-tool").get().requiredVersion)
}

detekt {
    buildUponDefaultConfig = true
    parallel = true
    config.from(rootProject.file("config/detekt/detekt.yml"))
}

// Load the custom rules from this module's own compiled output (no self-referential project
// dependency), so they run against the rule sources themselves.
dependencies {
    detektPlugins(files(sourceSets["main"].output))
}

// Documentation rules apply to production code only; restrict to the main source set.
tasks.named<Detekt>("detekt") {
    setSource(files("src/main/kotlin"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
