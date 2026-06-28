package de.seuhd.campuscoffee.tests.system

import de.seuhd.campuscoffee.api.dtos.TokenRequestDto
import de.seuhd.campuscoffee.api.dtos.TokenResponseDto
import de.seuhd.campuscoffee.domain.model.Role
import de.seuhd.campuscoffee.domain.tests.TestFixtures
import de.seuhd.campuscoffee.tests.SystemTestUtils.client
import de.seuhd.campuscoffee.tests.SystemTestUtils.encryptCredentials
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.client.returnResult

/**
 * Asserts the prod-profile security contract that no other test covers (all other JVM tests run the default
 * profile): the prod context boots with the fail-fast guards satisfied, the strict Content-Security-Policy,
 * the `Secure`/`HttpOnly`/`SameSite=Strict` session cookie, and the locked-down dev/actuator surface.
 */
class ProdProfileSecuritySystemTest : AbstractProdProfileSystemTest() {
    @Test
    fun `the prod context boots with the required secrets and a public base URL`() {
        // reaching this body means WeakDevSecretGuard and PublicBaseUrlGuard accepted the supplied prod config
        assertThat(seededUsers).isNotEmpty()
    }

    @Test
    fun `an unauthenticated response carries the strict prod Content-Security-Policy`() {
        val result =
            client()
                .get()
                .uri("/api/auth/public-key")
                .exchange()
                .returnResult<ByteArray>()
        assertThat(result.responseHeaders.getFirst("Content-Security-Policy")).isEqualTo(
            "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; " +
                "img-src 'self' data: blob:; font-src 'self' data:; connect-src 'self'; " +
                "object-src 'none'; base-uri 'self'; frame-ancestors 'none'"
        )
    }

    @Test
    fun `a successful login sets a Secure HttpOnly SameSite-Strict session cookie`() {
        val (login, password) = TestFixtures.rawCredentialsFor(Role.ADMIN)
        val result =
            client()
                .post()
                .uri("/api/auth/token")
                .contentType(MediaType.APPLICATION_JSON)
                .body(TokenRequestDto(encryptCredentials(login, password)))
                .exchange()
                .returnResult<TokenResponseDto>()
        assertThat(result.status.value()).isEqualTo(200)
        val setCookie = result.responseHeaders.getFirst(HttpHeaders.SET_COOKIE)
        assertThat(setCookie).isNotNull()
        assertThat(setCookie!!)
            .contains("campus_coffee_admin=")
            .contains("Secure")
            .contains("HttpOnly")
            .contains("SameSite=Strict")
            .contains("Path=/")
    }

    @Test
    fun `the dev data endpoint is not exposed under prod`() {
        val status =
            client()
                .get()
                .uri("/api/dev/data")
                .exchange()
                .returnResult<ByteArray>()
                .status
                .value()
        assertThat(status).isIn(401, 403)
    }

    @Test
    fun `the actuator env endpoint is not exposed under prod`() {
        val status =
            client()
                .get()
                .uri("/actuator/env")
                .exchange()
                .returnResult<ByteArray>()
                .status
                .value()
        assertThat(status).isNotEqualTo(200)
    }
}
