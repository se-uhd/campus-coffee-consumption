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

dependencies {
    jacocoAggregation(project(":domain"))
    jacocoAggregation(project(":api"))
    jacocoAggregation(project(":data"))
    jacocoAggregation(project(":application"))
}

reporting {
    reports {
        create<JacocoCoverageReport>("testCodeCoverageReport") {
            testSuiteName.set("test")
        }
    }
}

val aggregateReport = tasks.named<JacocoReport>("testCodeCoverageReport") {
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
