package de.seuhd.campuscoffee.api.openapi

import de.seuhd.campuscoffee.api.capability.CapabilityQrResponder
import de.seuhd.campuscoffee.api.capability.CapabilityUrlFactory
import de.seuhd.campuscoffee.api.controller.UserController
import de.seuhd.campuscoffee.api.mapper.UserDtoMapper
import de.seuhd.campuscoffee.api.security.CurrentUserProvider
import de.seuhd.campuscoffee.domain.ports.api.UserService
import io.swagger.v3.oas.models.Operation
import io.swagger.v3.oas.models.media.Content
import io.swagger.v3.oas.models.media.Schema
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Named.named
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.Arguments.arguments
import org.junit.jupiter.params.provider.MethodSource
import org.mockito.kotlin.mock
import org.springframework.core.ResolvableType
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.method.HandlerMethod
import java.util.stream.Stream

/**
 * Tests how [CrudOperationCustomizer] builds the OpenAPI responses for a controller method from its
 * `@CrudOperation` annotation and return type. It drives every annotated method on the real
 * [UserController], discovered reflectively so no method names are hard-coded, and checks the summary, the
 * response codes, the error schema, and that the success schema matches the return type (an array for a
 * list, a reference for a single object, none for void).
 */
class CrudOperationCustomizerTest {
    private val customizer = CrudOperationCustomizer()

    @ParameterizedTest
    @MethodSource("crudOperationMethods")
    fun `customize builds the responses for the CrudOperation and return type`(handlerMethod: HandlerMethod) {
        val crudOperation = handlerMethod.getMethodAnnotation(CrudOperation::class.java)!!
        val specs = crudOperation.operation.responseSpecifications

        val operation = customizer.customize(Operation(), handlerMethod)

        assertThat(operation.summary).isNotBlank()

        val responses = operation.responses
        val expectedStatuses = specs.map { it.httpStatus.value().toString() }.toMutableSet()
        if (crudOperation.roleRestricted) {
            // a role-restricted method documents 403 even though it is not in the shared operation spec
            expectedStatuses.add(HttpStatus.FORBIDDEN.value().toString())
        }
        assertThat(responses.keys).containsExactlyInAnyOrderElementsOf(expectedStatuses)

        for (spec in specs) {
            val response = responses[spec.httpStatus.value().toString()]!!
            assertThat(response.description).isNotBlank()
            if (spec.isErrorResponse) {
                assertThat(jsonSchema(response.content).`$ref`).contains("ErrorResponse")
            } else {
                assertSuccessContentMatchesReturnType(handlerMethod, response.content)
            }
        }

        if (crudOperation.roleRestricted) {
            val forbidden = responses[HttpStatus.FORBIDDEN.value().toString()]!!
            assertThat(forbidden.description).isNotBlank()
            assertThat(jsonSchema(forbidden.content).`$ref`).contains("ErrorResponse")
        }
    }

    @Test
    fun `customize handles a non-ResponseEntity return on a non-role-restricted operation`() {
        // a plain (non-ResponseEntity) return type and roleRestricted=false exercise the branches the
        // real controllers (all returning ResponseEntity, all admin-only) never reach
        val method = SampleController::class.java.getMethod("plain")
        val operation = customizer.customize(Operation(), HandlerMethod(SampleController(), method))

        // GET_BY_ID documents 200 and 404; without role restriction there is no 403
        assertThat(operation.responses.keys).containsExactlyInAnyOrder("200", "404")
        assertThat(jsonSchema(operation.responses["200"]!!.content).`$ref`).isNotBlank()
    }

    @Test
    fun `customize leaves a method without a CrudOperation annotation unchanged`() {
        // Object#toString carries no @CrudOperation, so the operation must pass through untouched
        val plain = HandlerMethod(userController(), Any::class.java.getMethod("toString"))

        val operation = customizer.customize(Operation(), plain)

        assertThat(operation.summary).isNull()
        assertThat(operation.responses).isNull()
    }

    /** A list return gives an array schema with item references, a single object a reference, void no body. */
    private fun assertSuccessContentMatchesReturnType(
        handlerMethod: HandlerMethod,
        content: Content?
    ) {
        var returnType = ResolvableType.forMethodReturnType(handlerMethod.method)
        if (returnType.rawClass == ResponseEntity::class.java) {
            returnType = returnType.getGeneric(0)
        }
        when (returnType.rawClass) {
            Void::class.java, Void.TYPE -> assertThat(content).isNull()
            List::class.java -> {
                val schema = jsonSchema(content)
                assertThat(schema.type).isEqualTo("array")
                assertThat(schema.items.`$ref`).isNotBlank()
            }
            else -> assertThat(jsonSchema(content).`$ref`).isNotBlank()
        }
    }

    private fun jsonSchema(content: Content?): Schema<*> {
        assertThat(content).isNotNull()
        return content!!["application/json"]!!.schema
    }

    /** A controller method with a plain (non-ResponseEntity) return and no role restriction. */
    private class SampleController {
        @CrudOperation(operation = de.seuhd.campuscoffee.api.openapi.Operation.GET_BY_ID, resource = Resource.USER)
        fun plain(): de.seuhd.campuscoffee.api.dtos.UserDto =
            de.seuhd.campuscoffee.api.dtos.UserDto(
                loginName = "x",
                emailAddress = "x@se.de",
                firstName = "X",
                lastName = "Y"
            )
    }

    companion object {
        private fun userController(): UserController =
            UserController(
                mock<UserService>(),
                mock<UserDtoMapper>(),
                mock<CurrentUserProvider>(),
                mock<CapabilityUrlFactory>(),
                mock<CapabilityQrResponder>()
            )

        /** Every `@CrudOperation`-annotated handler method on the real [UserController]. */
        @JvmStatic
        fun crudOperationMethods(): Stream<Arguments> {
            val controller = userController()
            return controller.javaClass.declaredMethods
                // skip the synthetic bridge methods the generic CrudController overrides generate;
                // they carry the annotation but a raw return type, which springdoc never passes
                .filter { it.isAnnotationPresent(CrudOperation::class.java) && !it.isBridge && !it.isSynthetic }
                .map {
                    arguments(named("${controller.javaClass.simpleName}.${it.name}", HandlerMethod(controller, it)))
                }.stream()
        }
    }
}
