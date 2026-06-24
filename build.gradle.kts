// Declare the Kotlin Gradle plugin once at the root (without applying it here) so a single classloader
// provides it to every subproject's convention plugins, instead of it being loaded more than once across
// the included build. See the Gradle note on "Kotlin Gradle plugin loaded multiple times".
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
}
