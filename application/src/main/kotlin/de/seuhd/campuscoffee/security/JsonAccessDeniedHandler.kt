package de.seuhd.campuscoffee.security

import de.seuhd.campuscoffee.api.exceptions.ErrorResponse
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.time.LocalDateTime

/**
 * Writes a filter-chain authorization rejection (an authenticated caller hitting a role-gated URL) as the
 * application's standard JSON [ErrorResponse] with 403, matching [JsonAuthenticationEntryPoint] and the
 * api layer's GlobalExceptionHandler so error bodies are uniform across the 401, 403, and domain paths.
 */
@Component
class JsonAccessDeniedHandler(
    private val objectMapper: ObjectMapper
) : AccessDeniedHandler {
    override fun handle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        accessDeniedException: AccessDeniedException
    ) {
        val body =
            ErrorResponse(
                errorCode = "AccessDeniedException",
                message = "You do not have permission to perform this action.",
                statusCode = HttpStatus.FORBIDDEN.value(),
                statusMessage = HttpStatus.FORBIDDEN.reasonPhrase,
                timestamp = LocalDateTime.now(),
                path = request.requestURI
            )
        response.status = HttpStatus.FORBIDDEN.value()
        response.contentType = "${MediaType.APPLICATION_JSON_VALUE};charset=${StandardCharsets.UTF_8.name()}"
        response.writer.write(objectMapper.writeValueAsString(body))
    }
}
