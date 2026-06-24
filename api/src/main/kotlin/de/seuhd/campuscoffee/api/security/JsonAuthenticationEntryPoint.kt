package de.seuhd.campuscoffee.api.security

import de.seuhd.campuscoffee.api.exceptions.ErrorResponse
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.core.AuthenticationException
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.time.LocalDateTime

/**
 * Writes an unauthenticated request's rejection as the application's standard JSON [ErrorResponse] with
 * 401, instead of Spring Security's default empty body or a `WWW-Authenticate` browser prompt. Mirrors
 * the shape produced by the api layer's GlobalExceptionHandler so error bodies are uniform.
 *
 * Provided in the starter; it only takes effect once the chain actually requires authentication
 * (Exercise 1): under the permissive `permitAll` chain nothing is rejected.
 */
@Component
class JsonAuthenticationEntryPoint(
    private val objectMapper: ObjectMapper
) : AuthenticationEntryPoint {
    override fun commence(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authException: AuthenticationException
    ) {
        val body =
            ErrorResponse(
                errorCode = "AuthenticationException",
                message = "Authentication is required to access this resource.",
                statusCode = HttpStatus.UNAUTHORIZED.value(),
                statusMessage = HttpStatus.UNAUTHORIZED.reasonPhrase,
                timestamp = LocalDateTime.now(),
                path = request.requestURI
            )
        response.status = HttpStatus.UNAUTHORIZED.value()
        response.contentType = "${MediaType.APPLICATION_JSON_VALUE};charset=${StandardCharsets.UTF_8.name()}"
        response.writer.write(objectMapper.writeValueAsString(body))
    }
}
