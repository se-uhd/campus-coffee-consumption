import com.github.gradle.node.npm.task.NpmTask
import info.solidsoft.gradle.pitest.PitestPluginExtension
import org.springframework.boot.gradle.tasks.bundling.BootJar
import org.springframework.boot.gradle.tasks.run.BootRun
import java.io.File

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
    // The startup data loaders log via KotlinLogging.logger {} (a Kotlin facade over SLF4J).
    implementation(libs.kotlin.logging)
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
    // otp-java is a non-transitive `implementation` of :data, so re-declare it here for the system-test
    // helpers that generate a current admin authenticator code (SystemTestUtils.currentAdminTotpCode).
    testImplementation(libs.otp.java)
    testImplementation(libs.junit.platform.suite)
    testImplementation(libs.cucumber.java)
    testImplementation(libs.cucumber.junit)
    testImplementation(libs.cucumber.junit.platform.engine)
    testImplementation(libs.cucumber.spring)
    testImplementation(libs.archunit)
}

// --- Angular frontend build ----------------------------------------------------------------------
// Resolve mise's shim directory (its default location, honoring the standard mise/XDG overrides). Each shim
// is a self-contained dispatcher to the mise binary, so an ABSOLUTE path to a shim runs the pinned node/npm
// with no PATH lookup at all (verified: it resolves the pinned node even from an empty environment).
val miseShimsDir: String =
    run {
        val dataDir =
            System.getenv("MISE_DATA_DIR")
                ?: System.getenv("XDG_DATA_HOME")?.let { "$it/mise" }
                ?: "${System.getProperty("user.home")}/.local/share/mise"
        "$dataDir/shims"
    }
val miseNpm = File(miseShimsDir, "npm")
val miseNpx = File(miseShimsDir, "npx")

// Use the mise-provided Node rather than downloading one, and run npm in the frontend project.
//
// The Gradle daemon is a long-lived JVM that captures the PATH it was FIRST started with and reuses it for
// every build. With `download = false`, node-gradle runs a bare `npm`, which the JVM resolves against THAT
// captured PATH (setting a task's own `environment` PATH does NOT change how the JVM looks up the command).
// So a daemon first launched outside a mise-activated environment (IntelliJ's Gradle integration, or a bare
// `gradle`) has no node on its PATH and every frontend task dies with
// "A problem occurred starting process 'command 'npm''", and keeps failing until that daemon is stopped.
// Fix: point npm/npx at mise's ABSOLUTE shim path when present, so node-gradle execs a fixed binary and
// never does a PATH lookup, independent of the daemon's PATH. On a machine without mise the shims are absent
// and the default PATH-resolved `npm` is kept unchanged.
node {
    download.set(false)
    nodeProjectDir.set(rootProject.layout.projectDirectory.dir("frontend"))
    if (miseNpm.exists()) npmCommand.set(miseNpm.absolutePath)
    if (miseNpx.exists()) npxCommand.set(miseNpx.absolutePath)
}

// The generateFrontendDtos task shells out to `bash -> npx`; bash resolves npx from its own process PATH (a
// real PATH lookup, unlike the JVM's), so that task gets the shim dir prepended to its PATH (added below).
val frontendExecPath: String =
    listOf(miseShimsDir, System.getenv("PATH").orEmpty())
        .filter { it.isNotEmpty() }
        .joinToString(File.pathSeparator)

// True when an `npm` executable exists in one of the PATH entries.
fun npmIsResolvable(path: String): Boolean =
    path.split(File.pathSeparator).any { dir ->
        dir.isNotEmpty() && (File(dir, "npm").exists() || File(dir, "npm.cmd").exists())
    }

// Whether npm will actually resolve for the frontend tasks: either the mise shim is used (absolute, always
// resolvable) or a plain `npm` is on the daemon's real PATH. Checked at execution to fail fast (below).
val npmWillResolve: Boolean = miseNpm.exists() || npmIsResolvable(System.getenv("PATH").orEmpty())

// Actionable failure shown when npm cannot be found for a frontend task, in place of the plugin's cryptic
// "A problem occurred starting process 'command 'npm''".
val npmMissingMessage =
    "npm was not found for the frontend build. This project builds the Angular SPA with the mise-provided " +
        "Node (mise.toml: node = '24').\n" +
        "  - Install the toolchain:   mise install\n" +
        "  - Run Gradle through mise: mise exec -- gradle <task>\n" +
        "If you use mise and this persists, a stale Gradle daemon captured a PATH without Node: run " +
        "'mise exec -- gradle --stop' and retry."

tasks.withType<NpmTask>().configureEach {
    doFirst {
        if (!npmWillResolve) {
            throw GradleException(npmMissingMessage)
        }
    }
}

// `npm ci` installs the locked dependencies reproducibly; cached on package.json/lock.
val frontendInstall by tasks.registering(NpmTask::class) {
    description = "Installs the frontend's locked npm dependencies (npm ci)."
    args.set(listOf("ci"))
    inputs.files(
        rootProject.file("frontend/package.json"),
        rootProject.file("frontend/package-lock.json")
    )
    outputs.dir(rootProject.file("frontend/node_modules"))
}

// Regenerates the frontend TypeScript DTOs from the committed backend OpenAPI spec
// (frontend/src-gen/api-docs.json) via scripts/generate-frontend-dtos.sh, which runs openapi-generator
// (models only) into frontend/src/app/api/model. The script is a hash-skip wrapper, so this is a fast
// no-op when the spec is unchanged; the inputs/outputs below let Gradle skip the task entirely when
// neither the spec nor the generated model dir changed. The script's npx uses the mise-provided Node by
// inheriting it from PATH (the node block above sets download = false). frontendInstall must run first so
// the pinned @openapitools/openapi-generator-cli devDependency is present for `npx --no-install`.
val generateFrontendDtos by tasks.registering(Exec::class) {
    description = "Generates the frontend DTOs from the backend OpenAPI spec (openapi-generator, models only)."
    dependsOn(frontendInstall)
    workingDir(rootProject.projectDir)
    commandLine("bash", "scripts/generate-frontend-dtos.sh")
    // the script's npx needs node on PATH; bash resolves it from this PATH (see the node block above)
    doFirst { environment("PATH", frontendExecPath) }
    inputs.file(rootProject.file("frontend/src-gen/api-docs.json"))
    inputs.file(rootProject.file("scripts/generate-frontend-dtos.sh"))
    outputs.dir(rootProject.file("frontend/src/app/api/model"))
    outputs.file(rootProject.file("frontend/src-gen/.api-docs.hash"))
}

// Refreshing the committed OpenAPI spec + frontend DTOs from the live app. The drift gate
// (DevSystemTests."the committed OpenAPI spec matches the live spec") fails `gradle build` when the
// committed frontend/src-gen/api-docs.json diverges from the running spec; this task regenerates it.
// writeOpenApiSpec runs that gate test, which writes the canonical live spec to build/openapi/api-docs.json
// even when it currently drifts (ignoreFailures), so refreshOpenApiSpec can copy it over the committed file
// and regenerate the DTOs. Replaces the old manual bootJar/curl refresh.
val writeOpenApiSpec by tasks.registering(Test::class) {
    description = "Writes build/openapi/api-docs.json from the live app via the OpenAPI drift gate (ignoring drift)."
    dependsOn(tasks.named("testClasses"))
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform()
    filter { includeTestsMatching("de.seuhd.campuscoffee.tests.system.DevSystemTests") }
    ignoreFailures = true
    outputs.upToDateWhen { false }
}

val refreshOpenApiSpec by tasks.registering(Copy::class) {
    description = "Refreshes the committed OpenAPI spec and the frontend DTOs from the live app."
    dependsOn(writeOpenApiSpec)
    from(layout.buildDirectory.file("openapi/api-docs.json"))
    into(rootProject.file("frontend/src-gen"))
    finalizedBy(generateFrontendDtos)
}

// `npm run build` produces frontend/dist/frontend/browser; cached on the sources. Depends on
// generateFrontendDtos because the generated DTOs (frontend/src/app/api) are part of the build's sources.
val frontendBuild by tasks.registering(NpmTask::class) {
    description = "Builds the Angular SPA bundled into the boot jar (npm run build)."
    dependsOn(frontendInstall, generateFrontendDtos)
    args.set(listOf("run", "build"))
    inputs.dir(rootProject.file("frontend/src"))
    inputs.files(
        rootProject.file("frontend/angular.json"),
        rootProject.file("frontend/tsconfig.json"),
        rootProject.file("frontend/tsconfig.app.json")
    )
    outputs.dir(rootProject.file("frontend/dist"))
}

// `npm run lint` runs the frontend's static analysis (ESLint via angular-eslint + Stylelint), mirroring
// the backend's ktlint/detekt gates. Wired into `check` below so `gradle build`/`gradle check` fails on a
// frontend lint violation just as it does on a Kotlin one. Cached on the linted sources and the tool
// configs; produces no artifact, so an up-to-date marker file stands in for an output.
val frontendLint by tasks.registering(NpmTask::class) {
    description = "Lints the Angular SPA (ESLint + Stylelint via npm run lint)."
    // generateFrontendDtos writes into frontend/src/app/api, which is under the linted source tree, so
    // it must run first (the generated DTOs are excluded from the lint by the eslint config, but the
    // input directory still references them).
    dependsOn(frontendInstall, generateFrontendDtos)
    args.set(listOf("run", "lint"))
    inputs.dir(rootProject.file("frontend/src"))
    inputs.files(
        rootProject.file("frontend/eslint.config.js"),
        rootProject.file("frontend/.stylelintrc.json"),
        rootProject.file("frontend/.stylelintignore"),
        rootProject.file("frontend/angular.json"),
        rootProject.file("frontend/tsconfig.json"),
        rootProject.file("frontend/tsconfig.app.json")
    )
    val marker = layout.buildDirectory.file("frontend-lint.marker")
    outputs.file(marker)
    doLast { marker.get().asFile.writeText("ok") }
}

// `npm test` runs the Angular unit tests on Vitest (the package.json `test` script is `ng test --no-watch`).
// Wired into `check` below so a failing unit test fails `gradle build`/`gradle check`. Cached on the sources
// and the tool configs; produces no artifact, so an up-to-date marker file stands in for an output.
val frontendTest by tasks.registering(NpmTask::class) {
    description = "Runs the Angular unit tests on Vitest (npm test)."
    dependsOn(frontendInstall, generateFrontendDtos)
    args.set(listOf("test"))
    inputs.dir(rootProject.file("frontend/src"))
    inputs.files(
        rootProject.file("frontend/angular.json"),
        rootProject.file("frontend/tsconfig.json"),
        rootProject.file("frontend/tsconfig.spec.json")
    )
    val marker = layout.buildDirectory.file("frontend-test.marker")
    outputs.file(marker)
    doLast { marker.get().asFile.writeText("ok") }
}

// `npm run knip` detects dead code and unused dependencies in the SPA. Wired into `check` below so a new
// unused export or dependency fails the build. Cached on the sources and the tool configs; produces no
// artifact, so an up-to-date marker file stands in for an output.
val frontendKnip by tasks.registering(NpmTask::class) {
    description = "Runs Knip dead-code and unused-dependency detection on the SPA (npm run knip)."
    dependsOn(frontendInstall, generateFrontendDtos)
    args.set(listOf("run", "knip"))
    inputs.dir(rootProject.file("frontend/src"))
    inputs.files(
        rootProject.file("frontend/package.json"),
        rootProject.file("frontend/knip.json"),
        rootProject.file("frontend/angular.json"),
        rootProject.file("frontend/tsconfig.json"),
        rootProject.file("frontend/tsconfig.app.json")
    )
    val marker = layout.buildDirectory.file("frontend-knip.marker")
    outputs.file(marker)
    doLast { marker.get().asFile.writeText("ok") }
}

// `npm run format:check` verifies the SPA sources are Prettier-formatted. Wired into `check` below so an
// unformatted file fails the build. Cached on the sources and the tool config; produces no artifact, so an
// up-to-date marker file stands in for an output.
val frontendFormatCheck by tasks.registering(NpmTask::class) {
    description = "Checks the SPA sources are Prettier-formatted (npm run format:check)."
    dependsOn(frontendInstall, generateFrontendDtos)
    args.set(listOf("run", "format:check"))
    inputs.dir(rootProject.file("frontend/src"))
    inputs.files(
        rootProject.file("frontend/package.json"),
        rootProject.file("frontend/.prettierrc.json"),
        rootProject.file("frontend/.prettierignore")
    )
    val marker = layout.buildDirectory.file("frontend-format-check.marker")
    outputs.file(marker)
    doLast { marker.get().asFile.writeText("ok") }
}

// Run the frontend lint, unit tests, dead-code check, and format check as part of `check` (and therefore
// `build`), like ktlint/detekt on the backend.
tasks.named("check") {
    dependsOn(frontendLint, frontendTest, frontendKnip, frontendFormatCheck)
}

// Name the boot jar application.jar (version-independent) so the Dockerfile references a stable
// name instead of a version-coupled application-<version>.jar. The built SPA is bundled into the jar's
// classpath static resources, so the single Cloud Run image serves the app and the API from one origin.
// Only bootJar (part of `assemble`/`build`) triggers the npm build, so a bare `gradle test` does not.
//
// `-PskipFrontendBuild` (used by scripts/run-e2e.sh and scripts/run-e2e-coverage.sh) skips the production SPA build so an
// already-built, source-mapped ("coverage") SPA in frontend/dist is bundled as-is instead of being
// overwritten; the e2e coverage run needs the source maps to map browser V8 coverage back to .ts.
val skipFrontendBuild = providers.gradleProperty("skipFrontendBuild").isPresent
tasks.named<BootJar>("bootJar") {
    archiveFileName.set("application.jar")
    if (!skipFrontendBuild) {
        dependsOn(frontendBuild)
    }
    from(rootProject.file("frontend/dist/frontend/browser")) {
        into("BOOT-INF/classes/static")
    }
}

// `bootRun` serves static resources straight off the classpath; unlike `bootJar` it does NOT bundle the
// SPA, so a plain `gradle :application:bootRun` would serve only the API and 404 every SPA route (e.g. the
// root returns the JSON "No endpoint found for '/index.html'"). Build the SPA and place it on bootRun's
// classpath under `static/`, so the dev run serves the full app on :8081 exactly like the jar.
//
// Wired to `bootRun` ONLY, never to processResources/classes, so a bare `gradle test` (which triggers
// processResources) still skips the npm build and stays fast. It honors `-PskipFrontendBuild` the same way
// bootJar does. For live frontend reload, prefer the Angular dev server (`cd frontend && npm start`).
val stageFrontendForBootRun by tasks.registering(Copy::class) {
    description = "Stages the built Angular SPA under static/ on bootRun's classpath so the dev run serves it."
    if (!skipFrontendBuild) {
        dependsOn(frontendBuild)
    }
    from(rootProject.file("frontend/dist/frontend/browser"))
    into(layout.buildDirectory.dir("bootRunStatic/static"))
}
tasks.named<BootRun>("bootRun") {
    dependsOn(stageFrontendForBootRun)
    classpath(layout.buildDirectory.dir("bootRunStatic"))
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

    // Run the system, acceptance, and architecture tests across several JVM processes. Process-level
    // forking is the only safe form of parallelism here: SystemTestUtils is an `object` with shared
    // mutable state (the RestTestClient) and the test bases wipe the whole database between tests with
    // clearAll(), so two tests must never run concurrently against the same JVM or database. Each fork is
    // a separate JVM, so it gets its own SystemTestUtils and its own Testcontainers PostgreSQL instance
    // (the container lives in AbstractSystemTest's companion object, one per JVM), and JUnit runs the
    // classes within a fork serially, so two tests never touch the shared client at once and clearAll()
    // never wipes a running test's data. Leaving forkEvery at its default (0) reuses each fork JVM across
    // the classes it runs, so Spring's per-JVM context cache still pays off within the fork. Each
    // concurrent fork boots a Spring context and starts a PostgreSQL container, both of which cost memory,
    // so the fork count is capped; override it with -PtestForks=N (1 disables parallelism, e.g. on a small
    // CI runner).
    maxParallelForks =
        (project.findProperty("testForks") as String?)?.toInt()
            ?: (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4)

    // Cap each fork's heap so the forks cannot collectively overcommit memory.
    maxHeapSize = "1g"
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
