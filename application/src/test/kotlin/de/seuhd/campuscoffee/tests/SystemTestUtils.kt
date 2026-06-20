package de.seuhd.campuscoffee.tests

import de.seuhd.campuscoffee.api.dtos.TokenRequestDto
import de.seuhd.campuscoffee.api.dtos.TokenResponseDto
import de.seuhd.campuscoffee.domain.model.Role
import de.seuhd.campuscoffee.domain.tests.TestFixtures
import org.assertj.core.api.Assertions.assertThat
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.web.servlet.client.RestTestClient
import org.springframework.test.web.servlet.client.returnResult
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * Utilities for the system tests: the PostgreSQL container wiring, the shared RestTestClient, and helpers
 * for the two authentication mechanisms — an admin JWT minted at the token endpoint, and a member
 * capability token sent as the `X-Coffee-Token` header.
 */
object SystemTestUtils {
    /** The header a member authenticates with (their secret capability token). */
    const val COFFEE_TOKEN_HEADER = "X-Coffee-Token"

    private lateinit var client: RestTestClient

    /** Binds the shared [RestTestClient] to the running server on the given port. */
    fun configureClient(port: Int) {
        client = RestTestClient.bindToServer().baseUrl("http://localhost:$port").build()
    }

    /** The client bound to the running server. */
    fun client(): RestTestClient = client

    // Creates a PostgreSQL testcontainer. The container is AutoCloseable but deliberately not closed here:
    // callers keep it open for the whole test run and Testcontainers tears it down on JVM shutdown.
    @Suppress("resource")
    fun getPostgresContainer(): PostgreSQLContainer<*> =
        PostgreSQLContainer<Nothing>(DockerImageName.parse("postgres:18-alpine"))

    /** Points the Spring datasource at the given PostgreSQL testcontainer. */
    fun configurePostgresContainers(
        registry: DynamicPropertyRegistry,
        postgresContainer: PostgreSQLContainer<*>
    ) {
        registry.add("spring.datasource.url", postgresContainer::getJdbcUrl)
        registry.add("spring.datasource.username", postgresContainer::getUsername)
        registry.add("spring.datasource.password", postgresContainer::getPassword)
    }

    /** Mints a JWT for the given credentials via the token endpoint and returns the bearer value. */
    fun jwtFor(
        loginName: String,
        password: String
    ): String {
        val result =
            client
                .post()
                .uri("/api/auth/token")
                .contentType(MediaType.APPLICATION_JSON)
                .body(TokenRequestDto(loginName, password))
                .exchange()
                .returnResult<TokenResponseDto>()
        assertThat(result.status.value()).isEqualTo(200)
        return result.responseBody!!.token
    }

    /** The JWT bearer value for the seeded admin fixture. */
    fun adminBearer(): String =
        TestFixtures.rawCredentialsFor(Role.ADMIN).let { (login, password) -> "Bearer ${jwtFor(login, password)}" }

    /** The capability token of the seeded member fixture with the given login name. */
    fun memberToken(loginName: String): String = TestFixtures.rawCapabilityTokenFor(loginName)

    /** The status code of a response, without asserting it. */
    fun RestTestClient.ResponseSpec.statusCode(): Int = returnResult<ByteArray>().status.value()

    /** The Authorization header carrying the admin bearer token. */
    fun RestTestClient.RequestHeadersSpec<*>.withAdmin(): RestTestClient.RequestHeadersSpec<*> =
        header(HttpHeaders.AUTHORIZATION, adminBearer())

    /** The X-Coffee-Token header carrying the given member's capability token. */
    fun RestTestClient.RequestHeadersSpec<*>.withMember(loginName: String): RestTestClient.RequestHeadersSpec<*> =
        header(COFFEE_TOKEN_HEADER, memberToken(loginName))
}
