package de.seuhd.campuscoffee.tests.system

import de.seuhd.campuscoffee.Application
import de.seuhd.campuscoffee.api.dtos.TokenRequestDto
import de.seuhd.campuscoffee.tests.SystemTestUtils.configurePostgresContainers
import de.seuhd.campuscoffee.tests.SystemTestUtils.getPostgresContainer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.client.RestTestClient
import org.springframework.test.web.servlet.client.returnResult
import org.testcontainers.containers.PostgreSQLContainer

/**
 * System test for the login brute-force and DoS guard on `POST /api/auth/token`. It boots its own context with
 * the limiter enabled and a low failure budget, then sends malformed login attempts from a fixed client IP
 * (each a 400 that counts against the budget) until the next attempt is refused with 429, and confirms a
 * different client is unaffected. A malformed payload is used so the test needs no key material: it still
 * reaches the limiter (the failure still counts) without doing any real authentication.
 */
@SpringBootTest(
    classes = [Application::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
class LoginRateLimitSystemTest {
    @LocalServerPort
    private var port: Int = 0

    private lateinit var client: RestTestClient

    @BeforeEach
    fun beforeEach() {
        client = RestTestClient.bindToServer().baseUrl("http://localhost:$port").build()
    }

    @Test
    fun `the login endpoint returns 429 Too Many Requests after too many failed attempts from one client`() {
        val attacker = "198.51.100.23"
        // each malformed attempt is a 400 and consumes one unit of the client's failure budget
        repeat(MAX_FAILURES) {
            assertThat(postMalformed(attacker)).isEqualTo(400)
        }
        // the budget is now spent, so the next attempt from the same client is refused before any work
        assertThat(postMalformed(attacker)).isEqualTo(429)
        // a different client still has its own budget and is unaffected
        assertThat(postMalformed("203.0.113.7")).isEqualTo(400)
    }

    private fun postMalformed(clientIp: String): Int =
        client
            .post()
            .uri("/api/auth/token")
            .header("X-Forwarded-For", clientIp)
            .contentType(MediaType.APPLICATION_JSON)
            .body(TokenRequestDto("not-a-valid-jwe"))
            .exchange()
            .returnResult<ByteArray>()
            .status
            .value()

    private companion object {
        private const val MAX_FAILURES = 5

        @Suppress("unused")
        private val postgresContainer: PostgreSQLContainer<*> = getPostgresContainer().apply { start() }

        @JvmStatic
        @DynamicPropertySource
        fun configureProperties(registry: DynamicPropertyRegistry) {
            configurePostgresContainers(registry, postgresContainer)
            // enable the limiter for this test with a small, fast budget so the 429 is reached deterministically
            registry.add("campus-coffee.auth.rate-limit.enabled") { "true" }
            registry.add("campus-coffee.auth.rate-limit.max-failures") { MAX_FAILURES.toString() }
            registry.add("campus-coffee.auth.rate-limit.window") { "1h" }
        }
    }
}
