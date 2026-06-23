package de.seuhd.campuscoffee.tests.architecture

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.library.Architectures.layeredArchitecture
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices
import org.junit.jupiter.api.Test

/**
 * Architecture tests that ensure that the application follows the ports-and-adapters pattern.
 */
class ArchitectureTests {
    @Test
    fun `each layer depends only on its permitted layers`() {
        val classes =
            ClassFileImporter()
                .importPackages("de.seuhd.campuscoffee") // imports all sub-packages

        // the application module's own wiring: the root package (the Spring Boot app + startup loaders), its
        // security (Spring Security, JWT, UserDetailsService), web (SPA forwarding), and configuration
        // (CORS, the prod guard, the @ConfigurationProperties), plus the test sources. All of it is the
        // application layer, which no other layer may access.
        val applicationPackages =
            arrayOf(
                "de.seuhd.campuscoffee",
                "de.seuhd.campuscoffee.security..",
                "de.seuhd.campuscoffee.web..",
                "de.seuhd.campuscoffee.configuration..",
                "de.seuhd.campuscoffee.tests.."
            )

        layeredArchitecture()
            .consideringAllDependencies()
            .layer("api")
            .definedBy("de.seuhd.campuscoffee.api..")
            .layer("domain")
            .definedBy("de.seuhd.campuscoffee.domain..")
            .layer("data")
            .definedBy("de.seuhd.campuscoffee.data..")
            .layer("application")
            .definedBy(*applicationPackages)
            .whereLayer("api")
            .mayOnlyBeAccessedByLayers("application")
            .whereLayer("domain")
            .mayOnlyBeAccessedByLayers("api", "data", "application")
            .whereLayer("data")
            .mayOnlyBeAccessedByLayers("application")
            .whereLayer("application")
            .mayNotBeAccessedByAnyLayer()
            // every imported production/test class must belong to one of the layers above, so a new top-level
            // package cannot silently escape the layer rules (the gap that left web/configuration uncovered)
            .ensureAllClassesAreContainedInArchitecture()
            .check(classes)
    }

    @Test
    fun `the production packages are free of cycles`() {
        // No package may depend (even transitively) back on a package that depends on it, across every
        // module - e.g. data.configuration <-> data.persistence.eventsourcing. Each distinct package path is
        // its own slice, so legitimate one-way edges (like the decorators' eventsourcing -> implementations)
        // are not mistaken for cycles. Test sources are excluded; this is about the production structure.
        val productionClasses =
            ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("de.seuhd.campuscoffee")

        slices()
            .matching("de.seuhd.campuscoffee.(**)")
            .should()
            .beFreeOfCycles()
            .check(productionClasses)
    }
}
