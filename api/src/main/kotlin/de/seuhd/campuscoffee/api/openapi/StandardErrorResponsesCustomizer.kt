package de.seuhd.campuscoffee.api.openapi

import de.seuhd.campuscoffee.api.exceptions.ErrorResponse
import io.swagger.v3.oas.models.Operation
import io.swagger.v3.oas.models.media.Content
import io.swagger.v3.oas.models.media.MediaType
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.responses.ApiResponse
import io.swagger.v3.oas.models.responses.ApiResponses
import org.springdoc.core.customizers.OperationCustomizer
import org.springframework.stereotype.Component
import org.springframework.web.method.HandlerMethod

/**
 * Documents the standard error responses (400, 401, 403, 404, 409) on every handwritten controller
 * operation, so the OpenAPI spec carries the real error contract for the whole money / consumption /
 * summary / activity / expense / payment / auth surface, not just the inferred success code. Operations
 * annotated with [CrudOperation] already get rich, per-operation responses from [CrudOperationCustomizer],
 * so they are skipped; an error code an operation already documents is never overwritten. All error bodies
 * are the shared [ErrorResponse] schema.
 */
@Component
class StandardErrorResponsesCustomizer : OperationCustomizer {
    override fun customize(
        operation: Operation,
        handlerMethod: HandlerMethod
    ): Operation {
        // the CRUD operations are documented per-resource by CrudOperationCustomizer; do not double-add
        if (handlerMethod.getMethodAnnotation(CrudOperation::class.java) != null) {
            return operation
        }
        val responses = operation.responses ?: ApiResponses().also { operation.responses = it }
        for ((status, description) in STANDARD_ERRORS) {
            if (!responses.containsKey(status)) {
                responses.addApiResponse(
                    status,
                    ApiResponse().description(description).content(errorContent())
                )
            }
        }
        return operation
    }

    /** The JSON content referencing the shared [ErrorResponse] component schema. */
    private fun errorContent(): Content =
        Content().addMediaType(
            "application/json",
            MediaType().schema(Schema<Any>().`$ref`("#/components/schemas/" + ErrorResponse::class.java.simpleName))
        )

    private companion object {
        // the error codes the API can return; over-documenting (e.g. a 404 on an endpoint without a path id)
        // is the accepted OpenAPI convention, signalling "may occur", and is preferable to documenting only 200
        private val STANDARD_ERRORS =
            listOf(
                "400" to "The request is malformed or violates a validation rule.",
                "401" to "The request is unauthenticated (a missing, unknown, or rotated credential).",
                "403" to "The authenticated caller is not permitted to perform this operation.",
                "404" to "A referenced resource does not exist.",
                "409" to "The request conflicts with the current state of the resource."
            )
    }
}
