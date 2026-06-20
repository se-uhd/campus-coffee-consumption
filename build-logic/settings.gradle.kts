// IntelliJ flags this `repositories` block as unstable: dependencyResolutionManagement is an
// @Incubating Gradle API, and there is no stable alternative for declaring repositories centrally.
dependencyResolutionManagement {
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
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"
