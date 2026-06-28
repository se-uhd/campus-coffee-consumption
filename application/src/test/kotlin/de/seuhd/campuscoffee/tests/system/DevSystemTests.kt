package de.seuhd.campuscoffee.tests.system

import de.seuhd.campuscoffee.Application
import de.seuhd.campuscoffee.api.dtos.DevSummaryDto
import de.seuhd.campuscoffee.tests.SystemTestUtils.configurePostgresContainers
import de.seuhd.campuscoffee.tests.SystemTestUtils.getPostgresContainer
import de.seuhd.campuscoffee.tests.openapi.OpenApiSpecNormalizer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.client.RestTestClient
import org.springframework.test.web.servlet.client.returnResult
import org.testcontainers.containers.PostgreSQLContainer
import java.io.File

/**
 * System tests for the dev-only data endpoints, run under the `dev` profile so the `DevController` is
 * registered and the fixtures load on startup. Each test resets the data to the seeded fixtures first
 * (the endpoints share one application context). Also home to the OpenAPI drift gate (`dev` enables
 * springdoc), which keeps the committed `frontend/src-gen/api-docs.json` in sync with the live spec.
 */
@SpringBootTest(classes = [Application::class], webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
class DevSystemTests {
    @LocalServerPort
    private var port: Int = 0

    private lateinit var client: RestTestClient

    @BeforeEach
    fun setUp() {
        client = RestTestClient.bindToServer().baseUrl("http://localhost:$port").build()
        // reset to the seeded fixtures so each test starts from a known state
        client.put().uri("/api/dev/data").exchange()
    }

    private fun summary(): DevSummaryDto =
        client
            .get()
            .uri("/api/dev/data")
            .exchange()
            .returnResult<DevSummaryDto>()
            .responseBody!!

    @Test
    fun `GET dev data reports the seeded users and consumptions`() {
        val summary = summary()

        assertThat(summary.users).isEqualTo(5)
        assertThat(summary.consumptions).isEqualTo(5)
    }

    @Test
    fun `PUT dev data reloads the fixtures and reports the counts`() {
        val result =
            client
                .put()
                .uri("/api/dev/data")
                .exchange()
                .returnResult<DevSummaryDto>()

        assertThat(result.status.value()).isEqualTo(200)
        assertThat(result.responseBody!!.users).isEqualTo(5)
    }

    @Test
    fun `DELETE dev data clears all data`() {
        client.delete().uri("/api/dev/data").exchange()

        assertThat(summary().users).isEqualTo(0)
        assertThat(summary().consumptions).isEqualTo(0)
    }

    @Test
    fun `GET the OpenAPI document builds the customized spec`() {
        // dev enables springdoc; fetching the document runs the OpenAPI + CrudOperation customizers
        val result =
            client
                .get()
                .uri("/api/api-docs")
                .exchange()
                .returnResult<String>()

        assertThat(result.status.value()).isEqualTo(200)
        assertThat(result.responseBody!!).contains("openapi").contains("/consumption")
    }

    @Test
    fun `the committed OpenAPI spec matches the live spec`() {
        val live =
            client
                .get()
                .uri("/api/api-docs")
                .exchange()
                .returnResult<String>()
                .responseBody!!
        val canonicalLive = OpenApiSpecNormalizer.canonical(live)

        // Always write the canonical live spec so `gradle :application:refreshOpenApiSpec` can copy it into
        // the committed location even when the committed spec currently drifts (that run ignores this
        // assertion's failure).
        val out = File(System.getProperty("user.dir"), "build/openapi/api-docs.json")
        out.parentFile.mkdirs()
        out.writeText(canonicalLive)

        val committed = repoRoot().resolve("frontend/src-gen/api-docs.json").readText()
        assertThat(canonicalLive)
            .withFailMessage(
                "The committed frontend/src-gen/api-docs.json has drifted from the live OpenAPI spec. " +
                    "Run `gradle :application:refreshOpenApiSpec` and commit the updated spec and regenerated DTOs."
            ).isEqualTo(OpenApiSpecNormalizer.canonical(committed))
    }

    private fun repoRoot(): File {
        val start = System.getProperty("user.dir")
        var dir = File(start)
        while (!File(dir, "settings.gradle.kts").exists()) {
            dir = requireNotNull(dir.parentFile) { "settings.gradle.kts not found above $start" }
        }
        return dir
    }

    companion object {
        private val postgresContainer: PostgreSQLContainer<*> = getPostgresContainer().apply { start() }

        @JvmStatic
        @DynamicPropertySource
        fun configureProperties(registry: DynamicPropertyRegistry) {
            configurePostgresContainers(registry, postgresContainer)
        }
    }
}
