package de.seuhd.campuscoffee.api.exceptions

import de.seuhd.campuscoffee.domain.exceptions.ConcurrentUpdateException
import de.seuhd.campuscoffee.domain.exceptions.ConflictException
import de.seuhd.campuscoffee.domain.exceptions.DeletionConflictException
import de.seuhd.campuscoffee.domain.exceptions.DuplicationException
import de.seuhd.campuscoffee.domain.exceptions.ForbiddenException
import de.seuhd.campuscoffee.domain.exceptions.MissingFieldException
import de.seuhd.campuscoffee.domain.exceptions.NotFoundException
import de.seuhd.campuscoffee.domain.exceptions.ValidationException
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.validation.ConstraintViolationException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.ResponseEntity
import org.springframework.security.core.AuthenticationException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.context.request.ServletWebRequest
import org.springframework.web.context.request.WebRequest
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler
import org.springframework.web.servlet.resource.NoResourceFoundException
import java.time.LocalDateTime

/**
 * Global exception handler for all controllers, producing a standardized [ErrorResponse] body.
 *
 * Extends [ResponseEntityExceptionHandler] so the standard Spring MVC exceptions (wrong HTTP method,
 * unsupported/unacceptable media type, missing parameter, unknown path, ...) are mapped to their
 * correct status codes instead of the generic 500 fallback; [handleExceptionInternal] renders them
 * as [ErrorResponse]. Domain exceptions are mapped explicitly below.
 */
@ControllerAdvice
class GlobalExceptionHandler : ResponseEntityExceptionHandler() {
    /**
     * Unified handler for the mapped domain exceptions, returning the HTTP status configured for the
     * exception type and falling back to the generic handler for anything unmapped.
     *
     * @param exception the thrown domain exception
     * @param request   the current web request
     */
    @ExceptionHandler(
        NotFoundException::class,
        DuplicationException::class,
        ConcurrentUpdateException::class,
        DeletionConflictException::class,
        ConflictException::class,
        IllegalArgumentException::class,
        MissingFieldException::class,
        ValidationException::class,
        ForbiddenException::class
    )
    fun handleMappedException(
        exception: Exception,
        request: WebRequest
    ): ResponseEntity<ErrorResponse> {
        val config =
            EXCEPTION_MAPPINGS[exception.javaClass]
                ?: return handleGenericException(exception, request)
        log.warn { config.logMessage.replace("{}", exception.message.toString()) }
        return ResponseEntity
            .status(config.httpStatus)
            .body(errorBody(exception, config.httpStatus, request, exception.message))
    }

    /**
     * Maps an authentication failure raised inside a controller to 401. The token endpoint authenticates
     * the supplied credentials, and a wrong password raises an [AuthenticationException] there (not in the
     * filter chain), so the security entry point does not see it.
     *
     * @param exception the authentication failure
     * @param request   the current web request
     */
    @ExceptionHandler(AuthenticationException::class)
    fun handleAuthenticationException(
        exception: AuthenticationException,
        request: WebRequest
    ): ResponseEntity<ErrorResponse> {
        log.warn { "Authentication failed: ${exception.message}" }
        return ResponseEntity
            .status(HttpStatus.UNAUTHORIZED)
            .body(errorBody(exception, HttpStatus.UNAUTHORIZED, request, exception.message))
    }

    /**
     * Maps a failure to read the encrypted login payload to 400. A [LoginPayloadException] means the JWE
     * could not be parsed, decrypted, or deserialized; the client-facing message is fixed and the detail is
     * logged server-side only, so the response is not a decryption oracle. This is kept distinct from a
     * wrong-credentials failure (which decrypts cleanly and then raises an authentication failure -> 401).
     *
     * @param exception the login-payload failure
     * @param request   the current web request
     */
    @ExceptionHandler(LoginPayloadException::class)
    fun handleLoginPayloadException(
        exception: LoginPayloadException,
        request: WebRequest
    ): ResponseEntity<ErrorResponse> {
        log.warn(exception) { "Login payload could not be read" }
        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(errorBody(exception, HttpStatus.BAD_REQUEST, request, "Malformed login payload."))
    }

    /**
     * Maps a method-parameter constraint violation (a `@Validated` query/path-param bound, such as the paging
     * `@Max`/`@Min`/`@Positive` limits) to 400. Without this, a `ConstraintViolationException` would fall
     * through to the generic 500 handler even though an out-of-range parameter is a client error.
     *
     * @param exception the constraint violation raised for an out-of-range request parameter
     * @param request   the current web request
     */
    @ExceptionHandler(ConstraintViolationException::class)
    fun handleConstraintViolation(
        exception: ConstraintViolationException,
        request: WebRequest
    ): ResponseEntity<ErrorResponse> {
        val message =
            exception.constraintViolations.joinToString("; ") {
                "${it.propertyPath.toString().substringAfterLast('.')} ${it.message}"
            }
        log.warn { "Constraint violation: $message" }
        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(errorBody(exception, HttpStatus.BAD_REQUEST, request, message))
    }

    /**
     * Fallback handler for unexpected exceptions, returning HTTP 500. The unmapped invariant guards
     * (`IllegalStateException`, raised by Kotlin `error(...)`/`check(...)`) land here; the body is already
     * clean: the response message is the fixed "An unexpected error occurred." string, not the exception
     * message, so no internal detail leaks (the full exception is logged server-side only).
     *
     * @param exception the unexpected exception
     * @param request   the current web request
     */
    @ExceptionHandler(Exception::class)
    fun handleGenericException(
        exception: Exception,
        request: WebRequest
    ): ResponseEntity<ErrorResponse> {
        log.error(exception) { "Unexpected error occurred" }
        return ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(errorBody(exception, HttpStatus.INTERNAL_SERVER_ERROR, request, "An unexpected error occurred."))
    }

    /**
     * Renders bean-validation failures on a request body as an [ErrorResponse], building the message
     * from the field-level binding errors. Overrides the base handler to keep the field names in the
     * message.
     */
    override fun handleMethodArgumentNotValid(
        ex: MethodArgumentNotValidException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest
    ): ResponseEntity<Any>? {
        val message = ex.bindingResult.fieldErrors.joinToString("; ") { "${it.field} ${it.defaultMessage}" }
        log.warn { "Domain validation failed: $message" }
        val body: Any = errorBody(ex, status, request, message)
        return ResponseEntity.status(status).headers(headers).body(body)
    }

    /**
     * Renders the 404 for an unmapped path as a clean [ErrorResponse]. Spring raises
     * [NoResourceFoundException] when no handler (or static resource) matches the request; the base class
     * would surface its framework wording ("No static resource ...") and class name, so this overrides it
     * with a neutral `NotFound` code and an endpoint-oriented message.
     */
    override fun handleNoResourceFoundException(
        ex: NoResourceFoundException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest
    ): ResponseEntity<Any>? {
        val path = extractPath(request)
        log.warn { "No endpoint mapped for $path" }
        val body: Any = errorBody(ex, status, request, "No endpoint found for '$path'.", errorCode = "NotFound")
        return ResponseEntity.status(status).headers(headers).body(body)
    }

    /** Renders every exception handled by [ResponseEntityExceptionHandler] as a standard [ErrorResponse]. */
    override fun handleExceptionInternal(
        ex: Exception,
        body: Any?,
        headers: HttpHeaders,
        statusCode: HttpStatusCode,
        request: WebRequest
    ): ResponseEntity<Any>? {
        log.warn { "${ex.javaClass.simpleName} -> $statusCode" }
        val responseBody: Any = errorBody(ex, statusCode, request, ex.message)
        return ResponseEntity.status(statusCode).headers(headers).body(responseBody)
    }

    /** Assembles the standardized [ErrorResponse] body from the exception, status, request, and message. */
    private fun errorBody(
        exception: Exception,
        status: HttpStatusCode,
        request: WebRequest,
        message: String?,
        errorCode: String = exception.javaClass.simpleName
    ): ErrorResponse =
        ErrorResponse(
            errorCode = errorCode,
            message = message,
            statusCode = status.value(),
            statusMessage = HttpStatus.valueOf(status.value()).reasonPhrase,
            timestamp = LocalDateTime.now(),
            path = extractPath(request)
        )

    /** Extracts the request URI from the web request, or "unknown" when it is not a servlet request. */
    private fun extractPath(request: WebRequest): String =
        (request as? ServletWebRequest)?.request?.requestURI ?: "unknown"

    /**
     * Maps an exception type to the HTTP status to return and the log message template
     * (with `{}` as the placeholder for the exception message).
     */
    private data class ExceptionConfig(
        val httpStatus: HttpStatus,
        val logMessage: String
    )

    private companion object {
        private val log = KotlinLogging.logger {}

        private val EXCEPTION_MAPPINGS: Map<Class<out Exception>, ExceptionConfig> =
            mapOf(
                NotFoundException::class.java to ExceptionConfig(HttpStatus.NOT_FOUND, "Resource not found: {}"),
                DuplicationException::class.java to ExceptionConfig(HttpStatus.CONFLICT, "Duplicate resource: {}"),
                ConcurrentUpdateException::class.java to
                    ExceptionConfig(HttpStatus.CONFLICT, "Concurrent modification: {}"),
                DeletionConflictException::class.java to
                    ExceptionConfig(HttpStatus.CONFLICT, "Deletion conflict: {}"),
                ConflictException::class.java to
                    ExceptionConfig(HttpStatus.CONFLICT, "State conflict: {}"),
                IllegalArgumentException::class.java to ExceptionConfig(HttpStatus.BAD_REQUEST, "Bad request: {}"),
                MissingFieldException::class.java to ExceptionConfig(HttpStatus.BAD_REQUEST, "Bad request: {}"),
                ValidationException::class.java to
                    ExceptionConfig(HttpStatus.BAD_REQUEST, "Domain validation failed: {}"),
                ForbiddenException::class.java to
                    ExceptionConfig(HttpStatus.FORBIDDEN, "Forbidden: {}")
            )
    }
}
