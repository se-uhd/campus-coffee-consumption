import info.solidsoft.gradle.pitest.PitestPluginExtension

plugins {
    id("de.seuhd.campuscoffee.java-conventions")
    id("de.seuhd.campuscoffee.kotlin-conventions")
    id("de.seuhd.campuscoffee.kotlin-jpa-conventions")
    id("de.seuhd.campuscoffee.kotlin-kapt-conventions")
    id("de.seuhd.campuscoffee.jacoco-conventions")
    id("de.seuhd.campuscoffee.pitest-conventions")
}

dependencies {
    implementation(project(":domain"))

    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.postgresql)
    // The event-sourcing startup runner logs via KotlinLogging.logger {} (a Kotlin facade over SLF4J).
    implementation(libs.kotlin.logging)
    implementation(libs.spring.boot.flyway)
    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.database.postgresql)
    // Jackson 3 Kotlin module for the event sourcing JSON bodies (Jackson 3 databind bundles java.time).
    implementation(libs.jackson3.module.kotlin)
    // BCrypt/delegating password encoder for the PasswordHasher adapter (small, dependency-free).
    implementation(libs.spring.security.crypto)
    // ZXing generates the user capability URL QR codes (core builds the matrix, javase writes the PNG).
    implementation(libs.zxing.core)
    implementation(libs.zxing.javase)
    // Apache PDFBox lays those QR codes out as a printable PDF grid (the all-user QR sheet).
    implementation(libs.pdfbox)
    // otp-java generates and verifies the admin TOTP second factor (RFC 6238), hidden behind the TotpService port.
    implementation(libs.otp.java)

    // MapStruct is compile-only for the Kotlin mappers; kapt runs the processor that generates the impls.
    compileOnly(libs.mapstruct)
    testImplementation(libs.mapstruct)
    kapt(libs.mapstruct.processor)

    testImplementation(libs.testcontainers.postgresql)
}

configure<PitestPluginExtension> {
    targetClasses.set(listOf("de.seuhd.campuscoffee.data.*"))
}
