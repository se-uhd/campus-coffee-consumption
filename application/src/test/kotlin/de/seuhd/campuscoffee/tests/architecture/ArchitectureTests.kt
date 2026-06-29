package de.seuhd.campuscoffee.tests.architecture

import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.library.Architectures.layeredArchitecture
import com.tngtech.archunit.library.dependencies.SliceAssignment
import com.tngtech.archunit.library.dependencies.SliceIdentifier
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Configuration

/**
 * Architecture tests that ensure that the application follows the ports-and-adapters pattern.
 */
class ArchitectureTests {
    @Test
    fun `each layer depends only on its permitted layers`() {
        val classes =
            ClassFileImporter()
                .importPackages("de.seuhd.campuscoffee") // imports all sub-packages

        // the application module's own wiring: the root package (the Spring Boot app, the startup loaders,
        // and StartupDataInitializer) and the bootstrap/seeding @ConfigurationProperties under
        // `configuration`, plus the test sources. All of it is the application layer, which no other layer
        // may access. The web/security adapters and their config now live in the `api` layer, not here.
        val applicationPackages =
            arrayOf(
                "de.seuhd.campuscoffee",
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
        // module - e.g. data.implementations <-> data.persistence.events. Each distinct package path is
        // its own slice, so legitimate one-way edges (the read services' implementations -> persistence.projection,
        // and the EventSourced* decorators' implementations -> persistence.events) are not mistaken for cycles.
        // Test sources are excluded; this is about the production structure.
        val productionClasses =
            ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("de.seuhd.campuscoffee")

        // A `matching("...(**)")` pattern cannot capture a class that sits directly in the root package
        // (there is no sub-package segment to match), so the application main class and the startup loaders
        // would be silently excluded from cycle analysis. Assign slices explicitly instead, giving root-package
        // classes their own "(root)" slice so they participate too.
        slices()
            .assignedFrom(RootInclusiveSliceAssignment)
            .should()
            .beFreeOfCycles()
            .check(productionClasses)
    }

    @Test
    fun `production code depends on ports, never on Impl types`() {
        val productionClasses =
            ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("de.seuhd.campuscoffee")

        // The five EventSourced* data-service decorators are the one allowed exception: each must inject the
        // concrete relational *DataServiceImpl it wraps, because injecting the port would resolve to the
        // @Primary decorator itself and self-loop. The exemption also covers their synthetic users (the
        // lambda classes the Kotlin compiler generates for the delegating calls). This exemption is blanket (the
        // whole decorator is removed from the source set), so it is broader than that single delegate edge: a
        // decorator injecting another data-module adapter *Impl directly would not be caught here, nor by the
        // layer rule (same module). Accepted trade-off; today each decorator injects only its *DataServiceImpl
        // delegate. @Configuration classes are
        // exempt because composing the beans is their whole job. Everything else depends on the port, not the
        // impl. The target is restricted to our own *Impl classes so a Kotlin-runtime *Impl (e.g. a lambda's
        // FunctionReferenceImpl supertype) is not mistaken for a layering violation. The SOURCE set also
        // excludes *Impl-named classes (haveSimpleNameNotEndingWith("Impl")): each relational *DataServiceImpl /
        // *ServiceImpl extends a base *Impl (CrudDataServiceImpl / CrudServiceImpl), an inheritance edge that
        // would otherwise trip the rule. The trade-off is that one *Impl depending on another is not checked;
        // acceptable because the adapters are the leaves of the dependency graph and nothing does so today.
        val isEventSourcedDecorator =
            object : DescribedPredicate<JavaClass>("an EventSourced* data-service decorator or its users") {
                override fun test(javaClass: JavaClass): Boolean {
                    val name = javaClass.name.substringAfterLast('.')
                    return name.startsWith("EventSourced") && name.contains("DataService")
                }
            }
        val isOwnImplementation =
            object : DescribedPredicate<JavaClass>("a campus-coffee class whose simple name ends with 'Impl'") {
                override fun test(javaClass: JavaClass): Boolean =
                    javaClass.simpleName.endsWith("Impl") && javaClass.packageName.startsWith("de.seuhd.campuscoffee")
            }

        noClasses()
            .that()
            .haveSimpleNameNotEndingWith("Impl")
            .and()
            .areNotAnnotatedWith(Configuration::class.java)
            .and(DescribedPredicate.not(isEventSourcedDecorator))
            .should()
            .dependOnClassesThat(isOwnImplementation)
            .check(productionClasses)
    }

    /**
     * Slices every production class by its package path relative to `de.seuhd.campuscoffee`, assigning a
     * class that sits directly in the root package to a dedicated `(root)` slice so it is not dropped from
     * cycle analysis (an unqualified `matching("...(**)")` would ignore it).
     */
    private object RootInclusiveSliceAssignment : SliceAssignment {
        private const val BASE_PACKAGE = "de.seuhd.campuscoffee"

        override fun getIdentifierOf(javaClass: JavaClass): SliceIdentifier {
            val packageName = javaClass.packageName
            if (packageName != BASE_PACKAGE && !packageName.startsWith("$BASE_PACKAGE.")) {
                return SliceIdentifier.ignore()
            }
            val relative = packageName.removePrefix(BASE_PACKAGE).removePrefix(".")
            return SliceIdentifier.of(relative.ifEmpty { "(root)" })
        }

        override fun getDescription(): String = "campus-coffee package slices, including the root package"
    }
}
