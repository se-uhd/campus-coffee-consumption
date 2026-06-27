package de.seuhd.campuscoffee.api.openapi

import de.seuhd.campuscoffee.api.exceptions.ErrorResponse
import de.seuhd.campuscoffee.api.openapi.Resource.NONE
import io.swagger.v3.oas.models.Operation
import io.swagger.v3.oas.models.media.Content
import io.swagger.v3.oas.models.media.MediaType
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.responses.ApiResponse
import io.swagger.v3.oas.models.responses.ApiResponses
import org.springdoc.core.customizers.OperationCustomizer
import org.springframework.core.ResolvableType
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.web.method.HandlerMethod

/**
 * Populates OpenAPI operations from the [CrudOperation] annotation on the handler method: sets the
 * summary and the responses (status codes, descriptions, and success/error schemas).
 */
@Component
class CrudOperationCustomizer : OperationCustomizer {
    override fun customize(
        operation: Operation,
        handlerMethod: HandlerMethod
    ): Operation {
        val crudOperation = handlerMethod.getMethodAnnotation(CrudOperation::class.java)
        if (crudOperation != null) {
            val params =
                Parameters(
                    crudOperation.operation,
                    crudOperation.resource,
                    crudOperation.externalResource.takeIf { it != NONE }
                )
            operation.summary = crudOperation.operation.summaryTemplate(params)
            operation.responses = createResponses(params, handlerMethod, crudOperation.roleRestricted)
        }
        return operation
    }

    /**
     * Creates the API responses from the operation's response specifications, attaching the
     * ErrorResponse schema for error responses and the return-type schema for success responses.
     *
     * @param params         the resolved operation, resource, and external resource
     * @param handlerMethod  the handler method whose return type yields the success schema
     * @param roleRestricted whether the operation also answers 403 when the caller lacks the role
     */
    fun createResponses(
        params: Parameters,
        handlerMethod: HandlerMethod,
        roleRestricted: Boolean
    ): ApiResponses {
        val responses = ApiResponses()
        for (spec in params.operation.responseSpecifications) {
            val response = ApiResponse().description(formatDescription(spec, params))
            response.content(
                if (spec.isErrorResponse) createErrorResponseContent() else createSuccessResponseContent(handlerMethod)
            )
            responses.addApiResponse(spec.httpStatus.value().toString(), response)
        }
        // A role-restricted operation also answers 403 when the caller is authenticated but lacks the
        // role. Whether that applies depends on the resource (managing users needs ROLE_ADMIN, but the
        // user self-service endpoints do not), so it is declared per method via @CrudOperation rather
        // than baked into the shared operation spec.
        val forbidden = HttpStatus.FORBIDDEN.value().toString()
        if (roleRestricted && !responses.containsKey(forbidden)) {
            responses.addApiResponse(
                forbidden,
                ApiResponse()
                    .description("The authenticated user lacks the role required for this operation.")
                    .content(createErrorResponseContent())
            )
        }
        return responses
    }

    /**
     * Formats the description template with the regular or external resource name.
     */
    private fun formatDescription(
        spec: CrudResponseSpecification,
        params: Parameters
    ): String {
        val substitution = params.externalResourceName?.takeIf { spec.isExternalResource } ?: params.resourceName
        return String.format(spec.descriptionTemplate, substitution)
    }

    /** Builds the JSON content referencing the [ErrorResponse] component schema for an error response. */
    private fun createErrorResponseContent(): Content {
        val errorSchema = Schema<Any>().`$ref`("#/components/schemas/" + ErrorResponse::class.java.simpleName)
        return Content().addMediaType("application/json", MediaType().schema(errorSchema))
    }

    /**
     * Builds the success-response content from the handler's return type: an array schema for a list,
     * a reference for a single object, or null for void.
     */
    private fun createSuccessResponseContent(handlerMethod: HandlerMethod): Content? {
        val returnType = unwrapResponseEntity(ResolvableType.forMethodReturnType(handlerMethod.method))
        val schema =
            when (returnType.rawClass) {
                Void::class.java, Void.TYPE -> return null
                List::class.java -> arraySchema(returnType.getGeneric(0))
                else -> refSchema(returnType)
            }
        return Content().addMediaType("application/json", MediaType().schema(schema))
    }

    /** Unwraps a `ResponseEntity<T>` return type to `T`; other return types pass through unchanged. */
    private fun unwrapResponseEntity(returnType: ResolvableType): ResolvableType =
        if (returnType.rawClass == ResponseEntity::class.java) returnType.getGeneric(0) else returnType

    /** An array schema whose items reference the component schema of the given element type. */
    private fun arraySchema(itemType: ResolvableType): Schema<*> =
        Schema<Any>().apply {
            type = "array"
            items = refSchema(itemType)
        }

    /** A reference (`$ref`) to the component schema for the given type. */
    private fun refSchema(type: ResolvableType): Schema<*> =
        Schema<Any>().`$ref`("#/components/schemas/" + type.rawClass!!.simpleName)
}
