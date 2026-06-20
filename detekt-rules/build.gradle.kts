plugins {
    id("de.seuhd.campuscoffee.detekt-rules-conventions")
}

dependencies {
    // detekt-api brings the rule base classes and (via apiElements) the Kotlin compiler PSI the
    // rules visit; it is provided by detekt at runtime, so compileOnly is enough for the main code.
    compileOnly(libs.detekt.api)

    // The tests run each rule against a compiled snippet: detekt-api for the rule types, and
    // detekt-test-utils to turn a Kotlin string into a KtFile (see RuleTestSupport).
    testImplementation(libs.detekt.api)
    testImplementation(libs.detekt.test.utils)
    testImplementation(enforcedPlatform(libs.junit.bom))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly(libs.junit.platform.launcher)
}
