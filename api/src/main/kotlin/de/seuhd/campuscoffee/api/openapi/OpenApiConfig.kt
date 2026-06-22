package de.seuhd.campuscoffee.api.openapi

import de.seuhd.campuscoffee.api.exceptions.ErrorResponse
import io.swagger.v3.core.converter.ModelConverters
import io.swagger.v3.oas.annotations.OpenAPIDefinition
import io.swagger.v3.oas.annotations.info.Info
import io.swagger.v3.oas.models.media.Schema
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

    /**
     * Drops the `null` member from the type of every `required` property in every component schema.
     *
     * The request DTOs declare their fields with nullable Kotlin types (`String?`, `Int?`) so a body
     * that omits a field still deserializes and is then rejected by the `@NotNull`/`@NotBlank` bean
     * validation. Under OpenAPI 3.1, springdoc reflects that Kotlin nullability as `type: [..., "null"]`
     * while the same `@NotNull` marks the property `required`, which is contradictory: a required field
     * is never null in a valid payload. Normalizing "required ⇒ not nullable" makes the generated
     * frontend DTO types honest (`loginName: string`, not `string | null`) without weakening the
     * server-side validation.
     */
    @Bean
    fun requiredFieldsNotNullableCustomizer(): OpenApiCustomizer =
        OpenApiCustomizer { openApi ->
            openApi.components
                ?.schemas
                ?.values
                ?.forEach(::dropNullFromRequiredProperties)
        }

    /**
     * Removes the `null` type member (and clears the legacy `nullable` flag) from every `required` property
     * of [schema], so a required field reads as non-nullable in the spec and the generated DTOs.
     *
     * @param schema the component schema whose required properties to normalize
     */
    private fun dropNullFromRequiredProperties(schema: Schema<*>) {
        val required = schema.required ?: return
        val properties = schema.properties ?: return
        for (name in required) {
            val property = properties[name] ?: continue
            property.types?.takeIf { "null" in it }?.let { types ->
                property.types = types - "null"
            }
            if (property.nullable == true) {
                property.nullable = false
            }
        }
    }
}
