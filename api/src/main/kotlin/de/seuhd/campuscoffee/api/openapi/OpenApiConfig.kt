package de.seuhd.campuscoffee.api.openapi

import de.seuhd.campuscoffee.api.exceptions.ErrorResponse
import io.swagger.v3.core.converter.ModelConverters
import io.swagger.v3.oas.annotations.OpenAPIDefinition
import io.swagger.v3.oas.annotations.info.Info
import org.springdoc.core.customizers.OpenApiCustomizer
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.info.BuildProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * OpenAPI/Swagger configuration: global API metadata and registration of the ErrorResponse schema.
 * The version is set at runtime from the build (see [apiVersionCustomizer]) rather than hard-coded
 * in the annotation.
 */
@Configuration
@OpenAPIDefinition(
    info =
        Info(
            title = "CampusCoffeeConsumption API",
            description =
                "REST API for tracking the coffee consumption of SE@UHD group members: members bump their " +
                    "own count via a secret capability link, and admins manage members and adjust counts."
        )
)
class OpenApiConfig {
    /**
     * Sets the OpenAPI document version from the build's [BuildProperties], falling back to "dev"
     * when the build info resource is absent. Keeps the version in one place: the Gradle build.
     *
     * @param buildProperties the build info provider, empty when the build info resource is absent
     */
    @Bean
    fun apiVersionCustomizer(buildProperties: ObjectProvider<BuildProperties>): OpenApiCustomizer =
        OpenApiCustomizer { openApi ->
            openApi.info?.version = buildProperties.ifAvailable?.version ?: "dev"
        }

    /**
     * Registers the ErrorResponse schema in the OpenAPI components. It is only referenced
     * programmatically (by the custom CRUD annotations), so it would otherwise be absent from the
     * Swagger UI schema list.
     */
    @Bean
    fun errorResponseSchemaCustomizer(): OpenApiCustomizer =
        OpenApiCustomizer { openApi ->
            ModelConverters
                .getInstance()
                .read(ErrorResponse::class.java)
                .forEach { (name, schema) -> openApi.components.addSchemas(name, schema) }
        }
}
