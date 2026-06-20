import com.github.gradle.node.npm.task.NpmTask
import info.solidsoft.gradle.pitest.PitestPluginExtension
import org.springframework.boot.gradle.tasks.bundling.BootJar

plugins {
    id("de.seuhd.campuscoffee.java-conventions")
    id("de.seuhd.campuscoffee.kotlin-conventions")
    id("de.seuhd.campuscoffee.jacoco-conventions")
    id("de.seuhd.campuscoffee.pitest-conventions")
    alias(libs.plugins.spring.boot)
    // builds the Angular SPA and bundles it into the boot jar's static resources
    id("com.github.node-gradle.node") version "7.1.0"
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":api"))
    // Compile-scoped (not runtimeOnly): puts the data module's Spring configuration metadata on this
    // module's compile classpath, so the IDE resolves the campus-coffee.* keys in application.yaml.
    implementation(project(":data"))

    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.actuator)
    // Spring Security (the filter chain and the capability token filter) and OAuth2 resource server (JWT
    // bearer tokens). The starter ships a working-but-permissive setup; the security config tightens it.
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.boot.starter.oauth2.resource.server)

    // The JDBC driver reaches the runtime classpath transitively via data, but declaring it on the
    // deployable module makes the runtime dependency explicit and lets the IDE resolve the
    // driver-class-name in application.yaml.
    runtimeOnly(libs.postgresql)
    // The Cloud SQL connector named by the prod datasource URL's socketFactory; only used by the prod
    // profile (dev/tests use a plain JDBC URL), but declared so the prod image boots.
    runtimeOnly(libs.cloud.sql.postgres)

    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.assertj.core)
    testImplementation(libs.junit.platform.suite)
    testImplementation(libs.cucumber.java)
    testImplementation(libs.cucumber.junit)
    testImplementation(libs.cucumber.junit.platform.engine)
    testImplementation(libs.cucumber.spring)
    testImplementation(libs.archunit)
}

// --- Angular frontend build ----------------------------------------------------------------------
// Use the mise-provided Node on PATH rather than downloading one, and run npm in the frontend project.
node {
    download.set(false)
    nodeProjectDir.set(rootProject.layout.projectDirectory.dir("frontend"))
}

// `npm ci` installs the locked dependencies reproducibly; cached on package.json/lock.
val frontendInstall by tasks.registering(NpmTask::class) {
    args.set(listOf("ci"))
    inputs.files(
        rootProject.file("frontend/package.json"),
        rootProject.file("frontend/package-lock.json")
    )
    outputs.dir(rootProject.file("frontend/node_modules"))
}

// `npm run build` produces frontend/dist/frontend/browser; cached on the sources.
val frontendBuild by tasks.registering(NpmTask::class) {
    dependsOn(frontendInstall)
    args.set(listOf("run", "build"))
    inputs.dir(rootProject.file("frontend/src"))
    inputs.files(
        rootProject.file("frontend/angular.json"),
        rootProject.file("frontend/tsconfig.json"),
        rootProject.file("frontend/tsconfig.app.json")
    )
    outputs.dir(rootProject.file("frontend/dist"))
}

// Name the boot jar application.jar (version-independent) so the Dockerfile references a stable
// name instead of a version-coupled application-<version>.jar. The built SPA is bundled into the jar's
// classpath static resources, so the single Cloud Run image serves the app and the API from one origin.
// Only bootJar (part of `assemble`/`build`) triggers the npm build, so a bare `gradle test` does not.
tasks.named<BootJar>("bootJar") {
    archiveFileName.set("application.jar")
    dependsOn(frontendBuild)
    from(rootProject.file("frontend/dist/frontend/browser")) {
        into("BOOT-INF/classes/static")
    }
}

springBoot {
    // Write META-INF/build-info.properties so a BuildProperties bean exposes the version at runtime
    // (consumed by OpenApiConfig), keeping the version sourced from the build.
    buildInfo()
}

// Only the executable bootJar is consumed; drop the redundant plain library jar.
tasks.named("jar") {
    enabled = false
}

// The test suite signs and verifies JWTs with its own throwaway secret, independent of the application's
// dev default in application.yaml (and of any secret a real deployment supplies).
tasks.test {
    systemProperty("campus-coffee.jwt.secret", "test-only-hs256-secret-not-used-outside-the-test-suite")
}

// Cross-module mutation: mutate the api and data classes against this module's system and
// acceptance tests, the only tests that exercise the controllers. Opt-in and local:
// `gradle :application:pitest -Pmutation`.
configure<PitestPluginExtension> {
    targetClasses.set(listOf("de.seuhd.campuscoffee.api.*", "de.seuhd.campuscoffee.data.*"))
    // The api/data production classes are Kotlin, so their bytecode is under classes/kotlin/main; the
    // only classes under classes/java/main are the kapt-generated *MapperImpl, which are excluded anyway.
    additionalMutableCodePaths.set(
        listOf(
            project(":api")
                .layout.buildDirectory
                .dir("classes/kotlin/main")
                .get()
                .asFile,
            project(":data")
                .layout.buildDirectory
                .dir("classes/kotlin/main")
                .get()
                .asFile
        )
    )
}
tasks.named("pitest") {
    dependsOn(":api:classes", ":data:classes")
}
