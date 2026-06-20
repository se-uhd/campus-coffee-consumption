plugins {
    `kotlin-dsl`
}

repositories {
    gradlePluginPortal()
    mavenCentral()
    // detekt 2.0's Gradle plugin pulls in org.gradle.experimental:gradle-public-api, which is
    // published only to Gradle's own repository.
    maven {
        url = uri("https://repo.gradle.org/gradle/libs-releases")
        content { includeGroup("org.gradle.experimental") }
    }
}

// The convention plugins apply these Gradle plugins by id, so their jars must be on build-logic's
// classpath here.
dependencies {
    implementation(libs.spring.boot.gradle.plugin)
    implementation(libs.dependency.management.plugin)
    implementation(libs.pitest.gradle.plugin)
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.kotlin.allopen)
    implementation(libs.kotlin.noarg)
    implementation(libs.ktlint.gradle.plugin)
    implementation(libs.detekt.gradle.plugin)
}
