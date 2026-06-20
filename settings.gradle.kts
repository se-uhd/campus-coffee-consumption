pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

// IntelliJ flags this `repositories` block as unstable: dependencyResolutionManagement is an
// @Incubating Gradle API, and there is no stable alternative for declaring repositories centrally.
dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "campus-coffee-consumption"

include("domain", "api", "data", "application", "coverage", "detekt-rules")
