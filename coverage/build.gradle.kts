// Aggregates every module's JaCoCo execution data into one report and enforces the coverage gate
// (90% line, 80% branch).
plugins {
    base
    jacoco
    id("jacoco-report-aggregation")
    // The aggregation configuration resolves the modules' transitive runtime dependencies, whose
    // versions come from the Spring Boot BOM, so it is imported here as well.
    alias(libs.plugins.dependency.management)
}

repositories {
    mavenCentral()
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:${libs.versions.spring.boot.get()}")
    }
}

jacoco {
    toolVersion = libs.versions.jacoco.get()
}

// Resolvable configuration that pulls the JaCoCo agent runtime jar (the `-javaagent` payload) from Maven
// Central, so the e2e orchestration can launch the application jar under it. Pinned to the same JaCoCo
// version as the rest of the build.
val jacocoAgentJar by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false
}

dependencies {
    jacocoAggregation(project(":domain"))
    jacocoAggregation(project(":api"))
    jacocoAggregation(project(":data"))
    jacocoAggregation(project(":application"))

    jacocoAgentJar("org.jacoco:org.jacoco.agent:${libs.versions.jacoco.get()}:runtime@jar")
}

reporting {
    reports {
        create<JacocoCoverageReport>("testCodeCoverageReport") {
            testSuiteName.set("test")
        }
    }
}

// The JaCoCo .exec the end-to-end run writes when the application jar is launched under the agent (see
// scripts/run-e2e-coverage.sh). It is an OPTIONAL input to the aggregate report and gate: present after an
// e2e coverage run, absent on a plain `gradle build`. When present, the e2e HTTP traffic's coverage is
// merged into the same aggregate report and the same gate as the unit/system/acceptance coverage, so the
// e2e adds to (never replaces) the JVM coverage. When absent, the gate degrades to the in-JVM coverage
// alone rather than failing because the e2e has not run.
val e2eExecFile = layout.buildDirectory.file("jacoco/e2e.exec")
val e2eExec = files(e2eExecFile).filter { it.exists() }

val aggregateReport = tasks.named<JacocoReport>("testCodeCoverageReport") {
    // Merge the e2e exec (when present) into the aggregate so its lines count in the HTML/XML report too.
    executionData.from(e2eExec)
    reports {
        xml.required.set(true)
        csv.required.set(true)
    }
}

// Gate exclusions:
//   TestFixtures (test data shipped in domain/src/main), the Spring Boot entry point (Application),
//   and the generated MapStruct *MapperImpl classes. The dev-only data endpoints are covered by a
//   dev-profile system test, so they are not excluded.
val gateExclusions = listOf(
    "de/seuhd/campuscoffee/domain/tests/**",
    // glob (not `.*`) so the Kotlin file class (ApplicationKt) and companion ($Companion) are excluded too
    "**/Application*",
    "**/*MapperImpl.*",
)

val coverageGate = tasks.register<JacocoCoverageVerification>("coverageGate") {
    group = "verification"
    description = "Fails the build when aggregate line/branch coverage is below the gate."
    // The aggregate report's executionData already includes the e2e exec when present, so the gate counts
    // exactly the same coverage the report shows.
    executionData.from(aggregateReport.map { it.executionData })
    sourceDirectories.from(aggregateReport.map { it.sourceDirectories })
    classDirectories.from(aggregateReport.map { it.classDirectories.asFileTree.matching { exclude(gateExclusions) } })
    violationRules {
        rule {
            element = "BUNDLE"
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.90".toBigDecimal()
            }
            limit {
                counter = "BRANCH"
                value = "COVEREDRATIO"
                minimum = "0.80".toBigDecimal()
            }
        }
    }
}

tasks.named("check") {
    dependsOn(aggregateReport, coverageGate)
}

// Runs the Playwright e2e against the application jar launched under the JaCoCo agent, writing
// build/jacoco/e2e.exec (backend coverage, merged into the gate above on the next report) plus
// frontend/coverage-e2e/ (browser V8 coverage source-mapped to .ts). It resolves the agent jar from the
// jacocoAgentJar configuration and passes it to the script. Requires Docker PostgreSQL on :5432, Node on
// PATH, and Playwright's chromium installed (`npx playwright install --with-deps chromium`); see the
// script header. Opt-in and orchestration-heavy, not wired into `check`. After it runs, re-run
// `:coverage:coverageGate` (or `gradle build`) to fold e2e.exec into the gate.
tasks.register<Exec>("runE2eCoverage") {
    group = "verification"
    description = "Runs the Playwright e2e against the app under the JaCoCo agent; writes build/jacoco/e2e.exec."
    workingDir = rootProject.projectDir
    val agentJars = jacocoAgentJar
    inputs.files(agentJars)
    outputs.file(e2eExecFile)
    doFirst {
        environment("JACOCO_AGENT_JAR", agentJars.singleFile.absolutePath)
    }
    commandLine("bash", "scripts/run-e2e-coverage.sh")
}
