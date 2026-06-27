package de.seuhd.campuscoffee.tests

import com.nimbusds.jose.EncryptionMethod
import com.nimbusds.jose.JWEAlgorithm
import com.nimbusds.jose.JWEHeader
import com.nimbusds.jose.JWEObject
import com.nimbusds.jose.Payload
import com.nimbusds.jose.crypto.RSAEncrypter
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.util.Base64URL
import de.seuhd.campuscoffee.api.dtos.PublicKeyDto
import de.seuhd.campuscoffee.api.dtos.TokenRequestDto
import de.seuhd.campuscoffee.api.dtos.TokenResponseDto
import de.seuhd.campuscoffee.api.exceptions.ErrorResponse
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
 * for the two authentication mechanisms: an admin JWT minted at the token endpoint, and a user
 * capability token sent as the `X-Capability-Token` header. The token endpoint takes an encrypted payload,
 * so the admin helpers fetch the server's published public key and encrypt the credentials before posting.
 */
object SystemTestUtils {
    /** The header a user authenticates with (their secret capability token). */
    const val CAPABILITY_TOKEN_HEADER = "X-Capability-Token"

    // A fixed, test-only 2048-bit PKCS#8 RSA key, injected as the login-encryption key for every system
    // test (the default profile carries no fallback). It is not a secret: it guards only test data.
    private val TEST_PRIVATE_KEY_PEM =
        """
        -----BEGIN PRIVATE KEY-----
        MIIEvAIBADANBgkqhkiG9w0BAQEFAASCBKYwggSiAgEAAoIBAQCMOOezSrpFsR9K
        q97QmshS+ieeoI8YPLsXQI1ofgwmMFmz/sz+t2dt0GD3Pa55s7a52b/l202uRcE4
        LJz4xBw8oKP52lrVUvuWeGNgkPnBANOY/QzX9xN4vU1QG8lZbG7fxeCpX9NnNrpB
        TEyDThYS5mNuuoriXNU9ZaGnAhhGCUbB079uF0PE1PB2LE2ykjxKDk1JSR7Gks55
        koLPxIKEcqz5kG63uAU0dLPrz2mynnmTdy4syl2wMd/U/3Z6Cm9+8QdOG8h5Z+YH
        G5ug8Uw7ZYjMhWY7mS+owYgCLXIsDdVdzl9TjufV5cjEGpmGjIOrabIH1o2+yiwH
        /LQ/XpWzAgMBAAECggEADBDzgz6pc8jPmAXdKRdAqL9E/IX1elDziocwA+9gzUJ4
        3Z/N9RdEK7N0PKJOqsNXtHtz42wxLY9sFpDkOxXpFiB+q8fp7BR1eNfIOW2QSFbx
        XmdcHRNyVf/4MK4LcAoAKnplID4SO33+nhLaPKxSAvNXWBZuUBdL9DoUJIJuI0yV
        cAi45chRtvIV5+EqAosEGTJjHRSfIEm2izDh5+R+Y/yd8WSfDm7KMdobCTB46zP+
        1wyqwj2KGUcGYr4pcZaLaHa5W0rjDUEFZMmEvfUWHVrPtFCQNzH6E0qBFGOqQqmY
        E1Lnxi+AbW+ySo38DlpF2DSx5PBko64/YfTr1kNrGQKBgQDBuJvoOBGX8DwpTGL9
        uaAyDZnlaPo6Pfeyu2g8U0JbgIfkAwL5ZkM/punL9XykBt4QsKdPI2xla4PP+AuK
        loXlA9bgoyBfk6L6hJR0bI/kwrerqrVPS0clj9cZ0HGzVBlzozrpey6Q0IRluhMs
        2xkr1p1iwvb24pw5FDopAHOwCQKBgQC5TU1Qr3bWvRGrUMbX6xleF5KZ+FVrii3l
        13nHWQPI+3k2USgBOGfUHjMv5gD3kuEo2wbOikAEWfbSQEuutXdJOfpzkJMIeV1k
        W4+j+KwendZrtY74bBQnrp8yExqcwqvgZ56CZH7RIkpz2KaAyWVOr2/cKo3xx/Yo
        JREblzKO2wKBgC1n2NswMSd8vo0rg1RXKu4wc+7qkSQPnDw/Yuoo1bfew3s1HYBZ
        cM+9jrUooANOPMSKs1yAQArxjV60k/fy2gVYxge3FIJyd1PiuW0keQG0hhptk7u3
        OEDcmx1I1y2iO6j4DHnnTn3Q8gdp+s6buCWnUxJwAjTR3q4eSJeNrJAJAoGAGI2x
        rPtTF+k9qiGt93ZjiiA9gMFzMCjDJC2FKXEWG0+XJCdk0aSTvXuy9KnZfvSreSps
        oHmZOfphxkJWxPOutrlEAoQpt3m9ckrfoa6VwAjSHLuWEjzf/tIYrh3x7Muu0rFo
        Q4bldvjAPNF8XpxRHDgK7nWFEYCZkYA34BwMyvkCgYAt8w9ClP73fWCfd9+RRWKj
        noxv1P1tp6AKkWD8fZuKQmbyuSvdA7nfntvOSPEbzfCQtBVaVhbtzcdNaK/rrhIv
        NH9Di8f32Ny/HLs58NnrstnrtMA4qt8u1AOvwTLEu5TPnl7r+n6mbqy9sFbc+i+j
        +4KbDU0zX6Z6zoEhFzIPsQ==
        -----END PRIVATE KEY-----
        """.trimIndent()

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
        // The default profile no longer carries a JWT-secret fallback (only dev does), so the boot-time
        // JwtProperties validation needs a test secret; supply a fixed >= 32-byte one for every system test.
        registry.add("campus-coffee.jwt.secret") { "test-only-insecure-jwt-secret-at-least-32-bytes-long" }
        // Likewise the login-encryption key has no default fallback; supply the fixed test key so the token
        // endpoint can decrypt the credentials the admin helpers encrypt.
        registry.add("campus-coffee.login-encryption.private-key-pem") { TEST_PRIVATE_KEY_PEM }
    }

    /** The server's published RSA public key, rebuilt from the JWK at `GET /api/auth/public-key`. */
    fun publicKey(): RSAKey {
        val jwk =
            client
                .get()
                .uri("/api/auth/public-key")
                .exchange()
                .returnResult<PublicKeyDto>()
                .responseBody!!
        return RSAKey.Builder(Base64URL.from(jwk.n), Base64URL.from(jwk.e)).keyID(jwk.kid).build()
    }

    /**
     * Encrypts the credentials as a compact JWE for the token endpoint, by default under the server's
     * published key; pass another key to exercise the wrong-key path.
     */
    fun encryptCredentials(
        loginName: String,
        password: String,
        publicKey: RSAKey = publicKey()
    ): String {
        val header =
            JWEHeader
                .Builder(
                    JWEAlgorithm.RSA_OAEP_256,
                    EncryptionMethod.A256GCM
                ).keyID(publicKey.keyID)
                .build()
        // include a fresh `iat` (epoch millis) so the decryptor's replay-freshness check accepts the payload
        val payload =
            Payload(
                mapOf<String, Any>(
                    "loginName" to loginName,
                    "password" to password,
                    "iat" to System.currentTimeMillis()
                )
            )
        val jwe = JWEObject(header, payload)
        jwe.encrypt(RSAEncrypter(publicKey.toRSAPublicKey()))
        return jwe.serialize()
    }

    /** Posts an already-encrypted payload to the token endpoint and returns the status, without asserting it. */
    fun postToken(encryptedPayload: String): Int =
        client
            .post()
            .uri("/api/auth/token")
            .contentType(MediaType.APPLICATION_JSON)
            .body(TokenRequestDto(encryptedPayload))
            .exchange()
            .statusCode()

    /** Posts an already-encrypted payload and returns the parsed error-response body (for the 400 contract). */
    fun tokenErrorFor(encryptedPayload: String): ErrorResponse =
        client
            .post()
            .uri("/api/auth/token")
            .contentType(MediaType.APPLICATION_JSON)
            .body(TokenRequestDto(encryptedPayload))
            .exchange()
            .returnResult<ErrorResponse>()
            .responseBody!!

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
                .body(TokenRequestDto(encryptCredentials(loginName, password)))
                .exchange()
                .returnResult<TokenResponseDto>()
        assertThat(result.status.value()).isEqualTo(200)
        return result.responseBody!!.token
    }

    /** The JWT bearer value for the seeded admin fixture. */
    fun adminBearer(): String =
        TestFixtures.rawCredentialsFor(Role.ADMIN).let { (login, password) -> "Bearer ${jwtFor(login, password)}" }

    /** The capability token of the seeded user fixture with the given login name. */
    fun userToken(loginName: String): String = TestFixtures.rawCapabilityTokenFor(loginName)

    /** The status code of a response, without asserting it. */
    fun RestTestClient.ResponseSpec.statusCode(): Int = returnResult<ByteArray>().status.value()

    /** The Authorization header carrying the admin bearer token. */
    fun RestTestClient.RequestHeadersSpec<*>.withAdmin(): RestTestClient.RequestHeadersSpec<*> =
        header(HttpHeaders.AUTHORIZATION, adminBearer())

    /** The X-Capability-Token header carrying the given user's capability token. */
    fun RestTestClient.RequestHeadersSpec<*>.withUser(loginName: String): RestTestClient.RequestHeadersSpec<*> =
        header(CAPABILITY_TOKEN_HEADER, userToken(loginName))
}
