package de.seuhd.campuscoffee.api.exceptions

import de.seuhd.campuscoffee.domain.exceptions.NotFoundException
import de.seuhd.campuscoffee.domain.model.User
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.web.context.request.ServletWebRequest
import org.springframework.web.context.request.WebRequest
import java.util.UUID

/**
 * Unit tests for [GlobalExceptionHandler]'s direct handlers and the request-path extraction. The HTTP
 * status mapping for the common domain exceptions is exercised end to end by the system tests; these
 * cover the branches a system test does not reach: an authentication failure raised inside a controller,
 * the generic fallback for an unmapped exception, and a request that is not a [ServletWebRequest].
 */
class GlobalExceptionHandlerTest {
    private val handler = GlobalExceptionHandler()

    @Test
    fun `handleAuthenticationException returns 401 with the request path`() {
        val request = ServletWebRequest(MockHttpServletRequest("POST", "/api/auth/token"))

        val response = handler.handleAuthenticationException(BadCredentialsException("Bad credentials"), request)

        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(response.body?.path).isEqualTo("/api/auth/token")
    }

    @Test
    fun `handleMappedException maps a mapped exception and falls back to 500 for an unmapped one`() {
        val request = ServletWebRequest(MockHttpServletRequest("GET", "/api/users/1"))

        val mapped = handler.handleMappedException(NotFoundException(User::class.java, UUID(0L, 1L)), request)
        assertThat(mapped.statusCode).isEqualTo(HttpStatus.NOT_FOUND)

        // an exception type the handler is registered for but that has no status mapping falls back to 500
        val unmapped = handler.handleMappedException(IllegalStateException("unexpected"), request)
        assertThat(unmapped.statusCode).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR)
    }

    @Test
    fun `the request path is unknown when the request is not a ServletWebRequest`() {
        val nonServletRequest = mock<WebRequest>()

        val response = handler.handleGenericException(RuntimeException("boom"), nonServletRequest)

        assertThat(response.statusCode).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR)
        assertThat(response.body?.path).isEqualTo("unknown")
    }
}
