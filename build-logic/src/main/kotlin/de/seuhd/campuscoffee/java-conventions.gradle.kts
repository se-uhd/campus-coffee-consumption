package de.seuhd.campuscoffee

import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.process.CommandLineArgumentProvider

// Shared configuration for the library/application modules: the Java toolchain (also used by the
// Kotlin compilation), the Spring Boot BOM, and the test JVM args. JaCoCo's agent is added by
// campuscoffee.jacoco-conventions. The Java major version is sourced from the version catalog
// (libs `java`) so it cannot drift from the Kotlin target, mise, and the Docker image.
plugins {
    `java-library`
    id("io.spring.dependency-management")
}

val libs = the<VersionCatalogsExtension>().named("libs")
val javaVersion = libs.findVersion("java").get().requiredVersion

// project.group and project.version come from the root gradle.properties (`group`/`version`); that
// version is the source of truth, and the latest CHANGELOG.md release header must match it, enforced by
// scripts/check-version-sync.sh in CI.

// Align the Kotlin stdlib/reflect with the Kotlin plugin version (Boot 4 manages an older stdlib;
// the plugin needs >= 2.3 for jvmTarget 25).
extra["kotlin.version"] = libs.findVersion("kotlin").get().requiredVersion

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(javaVersion))
    }
}

repositories {
    mavenCentral()
}

// Retain method parameter names so Spring can bind @PathVariable/@RequestParam by name; without
// this, such requests fail with HTTP 400.
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-parameters")
}

// TEMPORARY Tomcat override. Spring Boot 4.1.1 manages Tomcat 11.0.24, which carries three critical
// advisories, all of them authentication or authorization bypasses in the embedded server this app's admin
// login runs on: GHSA-9xv2-5v5q-p794 (DIGEST authenticator, capture-replay), GHSA-gcx9-497g-6cp6 (improper
// access control) and GHSA-h3x4-894j-xpx5 (FORM authentication). All three are fixed in 11.0.25, a patch
// release within the same Tomcat line.
//
// It is set as a project property rather than through the BOM import's `bomProperty`, because
// io.spring.dependency-management substitutes a project property whose name matches a BOM property, and it
// must be assigned before the import below for that substitution to happen.
//
// The version lives in the catalog, because the coverage module imports the same BOM separately and needs
// the identical override; a second literal here would have drifted from it.
//
// This lasts only until a Spring Boot release manages 11.0.25 or newer. The fifth check in
// scripts/check-toolchain-versions.sh compares the override against the BOM on every build and fails once
// the BOM has caught up, so it cannot outlive its reason unnoticed; delete this line when it tells you to.
extra["tomcat.version"] = libs.findVersion("tomcat").get().requiredVersion

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:${libs.findVersion("spring-boot").get().requiredVersion}")
    }
}

dependencies {
    // Test stack shared by every module: JUnit 5 / Mockito / AssertJ via the starter, plus the
    // JUnit Platform launcher Gradle needs on the test runtime classpath (the Spring Boot BOM
    // manages its version but does not add the dependency). Production dependencies that only some
    // modules use (e.g., spring-boot-starter-web) live in those modules' build files.
    // Force a single JUnit Platform version: cucumber-junit-platform-engine pulls JUnit Platform
    // 1.x transitively, which clashes with the JUnit 6 that Spring 7 requires.
    testImplementation(enforcedPlatform(libs.findLibrary("junit-bom").get()))
    testImplementation(libs.findLibrary("spring-boot-starter-test").get())
    testImplementation(libs.findLibrary("mockito-kotlin").get())
    testRuntimeOnly(libs.findLibrary("junit-platform-launcher").get())
}

// Mockito's agent is the mockito-core jar itself; resolve just that jar on a dedicated
// non-transitive configuration to pass as -javaagent.
val mockitoAgent = configurations.create("mockitoAgent") {
    isCanBeConsumed = false
    isTransitive = false
}
dependencies {
    "mockitoAgent"(libs.findLibrary("mockito-core").get())
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    jvmArgs(
        "-XX:+EnableDynamicAgentLoading",
        "-Xshare:off",
        // JDK 25: silence the deprecated sun.misc.Unsafe (JEP 498) and restricted-native-access warnings the
        // forked test JVM would otherwise print (e.g. via Testcontainers/JNA). A forked test JVM does not
        // inherit the daemon flags from gradle.properties, so they are repeated here.
        "--sun-misc-unsafe-memory-access=allow",
        "--enable-native-access=ALL-UNNAMED",
    )
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf("-javaagent:${mockitoAgent.singleFile.absolutePath}")
    })
}
