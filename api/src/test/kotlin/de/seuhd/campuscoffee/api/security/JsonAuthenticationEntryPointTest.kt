package de.seuhd.campuscoffee.api.security

import de.seuhd.campuscoffee.api.exceptions.ErrorResponse
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.BadCredentialsException
import tools.jackson.databind.ObjectMapper
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Unit tests for [JsonAuthenticationEntryPoint], which renders an unauthenticated rejection as the
 * application's JSON [ErrorResponse] with status 401.
 */
@ExtendWith(MockitoExtension::class)
class JsonAuthenticationEntryPointTest {
    @Mock
    private lateinit var request: HttpServletRequest

    @Mock
    private lateinit var response: HttpServletResponse

    private val objectMapper = ObjectMapper()

    private val entryPoint by lazy { JsonAuthenticationEntryPoint(objectMapper) }

    @Test
    fun `commence writes a 401 ErrorResponse JSON body for the request path`() {
        val buffer = StringWriter()
        whenever(request.requestURI).thenReturn("/api/reviews")
        whenever(response.writer).thenReturn(PrintWriter(buffer))

        entryPoint.commence(request, response, BadCredentialsException("bad"))

        verify(response).status = HttpStatus.UNAUTHORIZED.value()
        val body = objectMapper.readValue(buffer.toString(), ErrorResponse::class.java)
        assertThat(body.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED.value())
        assertThat(body.errorCode).isEqualTo("AuthenticationException")
        assertThat(body.path).isEqualTo("/api/reviews")
    }
}
