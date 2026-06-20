import info.solidsoft.gradle.pitest.PitestPluginExtension

plugins {
    id("de.seuhd.campuscoffee.java-conventions")
    id("de.seuhd.campuscoffee.kotlin-conventions")
    id("de.seuhd.campuscoffee.kotlin-kapt-conventions")
    id("de.seuhd.campuscoffee.jacoco-conventions")
    id("de.seuhd.campuscoffee.pitest-conventions")
}

dependencies {
    // api re-exposes domain types in its public signatures (e.g., CrudController<User, UserDto, UUID>).
    api(project(":domain"))

    implementation(libs.spring.boot.starter.web)
    // `api` (not `implementation`): the public DTOs carry springdoc `@Schema` annotations, so the Swagger
    // annotation classes are part of api's compile contract. Exposing them lets the application module's
    // annotation processor resolve `@Schema(accessMode = ...)` instead of warning that the enum is missing.
    api(libs.springdoc.openapi.starter.webmvc.ui)
    implementation(libs.spring.boot.starter.validation)
    // Spring Security types used in the api layer: reading the authenticated principal
    // (CurrentUserProvider) and minting the JWT in the auth controller (the OAuth2 JWT encoder/claims).
    // ArchUnit gates layers, not libraries, so a Spring Security dependency here is allowed; the domain
    // stays free of it.
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.boot.starter.oauth2.resource.server)
    // Jackson support for the Kotlin DTOs (construction, nullability, defaults); Spring Boot auto-registers it.
    implementation(libs.jackson.module.kotlin)

    // MapStruct is compile-only for the Kotlin mappers; kapt runs the processor that generates the impls.
    compileOnly(libs.mapstruct)
    testImplementation(libs.mapstruct)
    kapt(libs.mapstruct.processor)
}

configure<PitestPluginExtension> {
    targetClasses.set(listOf("de.seuhd.campuscoffee.api.*"))
}
