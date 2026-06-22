import info.solidsoft.gradle.pitest.PitestPluginExtension

plugins {
    id("de.seuhd.campuscoffee.java-conventions")
    id("de.seuhd.campuscoffee.kotlin-conventions")
    id("de.seuhd.campuscoffee.jacoco-conventions")
    id("de.seuhd.campuscoffee.pitest-conventions")
}

dependencies {
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.tx)
    // The service implementations log via KotlinLogging.logger {} (a Kotlin facade over SLF4J).
    implementation(libs.kotlin.logging)
}

configure<PitestPluginExtension> {
    targetClasses.set(listOf("de.seuhd.campuscoffee.domain.*"))
}
