package de.seuhd.campuscoffee.tests.system

import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import de.seuhd.campuscoffee.api.dtos.PublicKeyDto
import de.seuhd.campuscoffee.api.dtos.TokenRequestDto
import de.seuhd.campuscoffee.api.dtos.UserDto
import de.seuhd.campuscoffee.domain.model.Role
import de.seuhd.campuscoffee.domain.model.User
import de.seuhd.campuscoffee.domain.model.persistedId
import de.seuhd.campuscoffee.domain.tests.TestFixtures
import de.seuhd.campuscoffee.tests.SystemTestUtils.CAPABILITY_TOKEN_HEADER
import de.seuhd.campuscoffee.tests.SystemTestUtils.client
import de.seuhd.campuscoffee.tests.SystemTestUtils.encryptCredentials
import de.seuhd.campuscoffee.tests.SystemTestUtils.jwtFor
import de.seuhd.campuscoffee.tests.SystemTestUtils.postToken
import de.seuhd.campuscoffee.tests.SystemTestUtils.statusCode
import de.seuhd.campuscoffee.tests.SystemTestUtils.tokenErrorFor
import de.seuhd.campuscoffee.tests.SystemTestUtils.withMember
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.client.returnResult

/**
 * System tests for the authentication and authorization rules of the two mechanisms: a member capability
 * token and the admin JWT.
 */
class AuthorizationSystemTests : AbstractSystemTest() {
    private val member = "maxmustermann"

    // Creates a second active admin (with a known password) so the seeded admin can be deactivated without
    // tripping the last-active-admin guard. Returns the created admin (used as the acting user that
    // deactivates the seeded admin in these tests).
    private fun createSecondAdmin(): User {
        val seededAdmin = seededUser("jane_doe")
        return userService.create(
            User(
                loginName = "second_admin",
                emailAddress = "second.admin@se.uni-heidelberg.de",
                firstName = "Second",
                lastName = "Admin",
                role = Role.ADMIN,
                password = "second-admin-password"
            ),
            seededAdmin
        )
    }

    @Test
    fun `a request with no credentials returns 401 Unauthorized`() {
        val status =
            client()
                .get()
                .uri("/api/summary")
                .exchange()
                .statusCode()

        assertThat(status).isEqualTo(401)
    }

    @Test
    fun `an unknown capability token returns 401 Unauthorized`() {
        val status =
            client()
                .get()
                .uri("/api/summary")
                .header(CAPABILITY_TOKEN_HEADER, "not-a-real-token")
                .exchange()
                .statusCode()

        assertThat(status).isEqualTo(401)
    }

    @Test
    fun `a deactivated member is read-only and cannot add a coffee`() {
        val admin = seededUser("jane_doe")
        val max = seededUser(member)
        userService.update(max.copy(active = false), admin)

        // the deactivated member still authenticates (a read works) but the mutation is forbidden
        val readStatus =
            client()
                .get()
                .uri("/api/summary")
                .withMember(member)
                .exchange()
                .statusCode()
        val writeStatus =
            client()
                .post()
                .uri("/api/consumption")
                .withMember(member)
                .exchange()
                .statusCode()

        assertThat(readStatus).isEqualTo(200)
        assertThat(writeStatus).isEqualTo(403)
    }

    @Test
    fun `a member capability token may not access admin user management`() {
        val status =
            client()
                .get()
                .uri(
                    "/api/users"
                ).accept(MediaType.APPLICATION_JSON)
                .withMember(member)
                .exchange()
                .statusCode()

        assertThat(status).isEqualTo(403)
    }

    @Test
    fun `a deactivated member updating their own profile returns 403 Forbidden`() {
        val admin = seededUser("jane_doe")
        val max = seededUser(member)
        userService.update(max.copy(active = false), admin)

        // the deactivated member still authenticates, but a profile edit is a mutation and is forbidden
        val status =
            client()
                .put()
                .uri("/api/profile")
                .contentType(MediaType.APPLICATION_JSON)
                .body(
                    UserDto(
                        loginName = member,
                        emailAddress = "new.email@example.com",
                        firstName = "New",
                        lastName = "Name"
                    )
                ).withMember(member)
                .exchange()
                .statusCode()

        assertThat(status).isEqualTo(403)
    }

    @Test
    fun `an anonymous request to a non-health actuator endpoint returns 401 Unauthorized`() {
        // /actuator/env is gated by the explicit /actuator/** ADMIN rule, which precedes the SPA GET
        // catch-all that would otherwise make it anonymously reachable.
        val status =
            client()
                .get()
                .uri("/actuator/env")
                .exchange()
                .statusCode()

        assertThat(status).isEqualTo(401)
    }

    @Test
    fun `an anonymous request to actuator health returns 200 OK`() {
        // health is the one actuator endpoint left public (the Docker/Cloud Run healthcheck relies on it),
        // so it must stay anonymously reachable even as every other actuator path is locked down. Together
        // with the 401 and 403 cases below this pins the actuator authorization contract: a regression that
        // reordered the security matchers (the SPA `GET /** -> permitAll` ahead of `/actuator/** -> ADMIN`)
        // would break one of the three.
        val status =
            client()
                .get()
                .uri("/actuator/health")
                .exchange()
                .statusCode()

        assertThat(status).isEqualTo(200)
    }

    @Test
    fun `a member request to a non-health actuator endpoint returns 403 Forbidden`() {
        // a ROLE_USER member is authenticated but not an admin, so the `/actuator/** -> ADMIN` rule forbids
        // it (403): actuator is closed to members as well as to anonymous callers.
        val status =
            client()
                .get()
                .uri("/actuator/env")
                .withMember(member)
                .exchange()
                .statusCode()

        assertThat(status).isEqualTo(403)
    }

    @Test
    fun `an anonymous request to the dev data endpoints returns 401 Unauthorized`() {
        // outside the dev profile the `/api/dev/**` permitAll rule is not registered (it is gated on the dev
        // profile), so the request falls through to the authenticated catch-all instead of being anonymously
        // reachable. The DevController is itself @Profile("dev"), so this is defense in depth; the tests run
        // under the default profile, where dev is not active.
        val status =
            client()
                .get()
                .uri("/api/dev/data")
                .exchange()
                .statusCode()

        assertThat(status).isEqualTo(401)
    }

    @Test
    fun `a deactivated admin requesting a token returns 401 Unauthorized`() {
        // a second admin must exist so the seeded admin can be deactivated (the last-active-admin guard)
        val secondAdmin = createSecondAdmin()
        val seededAdmin = seededUser("jane_doe")
        val (login, password) = TestFixtures.rawCredentialsFor(Role.ADMIN)
        // encrypt the (well-formed) credentials, then deactivate the seeded admin and try to log in as them
        val encrypted = encryptCredentials(login, password)
        userService.update(seededAdmin.copy(active = false), secondAdmin)

        // a disabled account is refused at login (DisabledException -> 401 via the global handler)
        assertThat(postToken(encrypted)).isEqualTo(401)
    }

    @Test
    fun `the public key endpoint returns a usable RSA encryption JWK without authentication`() {
        val result =
            client()
                .get()
                .uri("/api/auth/public-key")
                .exchange()
                .returnResult<PublicKeyDto>()

        assertThat(result.status.value()).isEqualTo(200)
        val jwk = result.responseBody!!
        assertThat(jwk.kty).isEqualTo("RSA")
        assertThat(jwk.alg).isEqualTo("RSA-OAEP-256")
        assertThat(jwk.use).isEqualTo("enc")
        assertThat(jwk.n).isNotBlank()
        assertThat(jwk.e).isNotBlank()
        assertThat(jwk.kid).isNotBlank()
    }

    @Test
    fun `a valid encrypted login issues a token that authorizes an admin endpoint`() {
        val (login, password) = TestFixtures.rawCredentialsFor(Role.ADMIN)

        val token = jwtFor(login, password)

        assertThat(token).isNotBlank()
        val status =
            client()
                .get()
                .uri("/api/users/me")
                .accept(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                .exchange()
                .statusCode()
        assertThat(status).isEqualTo(200)
    }

    @Test
    fun `a malformed login payload returns 400 Bad Request`() {
        // a body that is not a parseable JWE cannot be read, so it is a client error (400), distinct from the
        // 401 a readable-but-wrong credential gets: a 400 reveals nothing about whether the login exists
        assertThat(postToken("not-a-valid-jwe")).isEqualTo(400)
    }

    @Test
    fun `a login payload encrypted with a wrong key returns 400 Bad Request`() {
        val (login, password) = TestFixtures.rawCredentialsFor(Role.ADMIN)
        // a JWE encrypted under a key the server does not hold cannot be decrypted, so it is a malformed payload
        val wrongKey = RSAKeyGenerator(2048).keyID("wrong-key").generate()
        assertThat(postToken(encryptCredentials(login, password, wrongKey.toPublicJWK()))).isEqualTo(400)
    }

    @Test
    fun `a correctly encrypted payload with a wrong password returns 401 Unauthorized`() {
        val (login, _) = TestFixtures.rawCredentialsFor(Role.ADMIN)
        // a payload that decrypts cleanly but carries a wrong password is an authentication failure (401),
        // distinct from the 400 an undecryptable payload gets
        assertThat(postToken(encryptCredentials(login, "definitely-wrong"))).isEqualTo(401)
    }

    @Test
    fun `a blank login payload returns 400 Bad Request`() {
        // the @NotBlank bean-validation path (rejected before the decryptor is reached)
        assertThat(postToken("")).isEqualTo(400)
    }

    @Test
    fun `a missing login payload returns 400 Bad Request`() {
        val status =
            client()
                .post()
                .uri("/api/auth/token")
                .contentType(MediaType.APPLICATION_JSON)
                .body(TokenRequestDto(null))
                .exchange()
                .statusCode()

        assertThat(status).isEqualTo(400)
    }

    @Test
    fun `a malformed and a wrong-key payload return the same fixed 400 message, revealing nothing`() {
        val (login, password) = TestFixtures.rawCredentialsFor(Role.ADMIN)
        val wrongKey = RSAKeyGenerator(2048).keyID("wrong-key").generate()

        val malformed = tokenErrorFor("not-a-valid-jwe")
        val wrongKeyError = tokenErrorFor(encryptCredentials(login, password, wrongKey.toPublicJWK()))

        // both undecryptable cases produce the identical fixed message, so the 400 is not a decryption oracle
        assertThat(malformed.message).isEqualTo("Malformed login payload.")
        assertThat(wrongKeyError.message).isEqualTo("Malformed login payload.")
    }

    @Test
    fun `an in-flight admin JWT after deactivation returns 403 on an admin endpoint`() {
        val secondAdmin = createSecondAdmin()
        val seededAdmin = seededUser("jane_doe")
        val (login, password) = TestFixtures.rawCredentialsFor(Role.ADMIN)
        // mint a JWT while the admin is still active, then deactivate them
        val inFlight = jwtFor(login, password)
        userService.update(seededAdmin.copy(active = false), secondAdmin)

        // the still-valid token authenticates, but resolving the principal (here, /api/users/me, which
        // calls CurrentUserProvider.currentUser()) rejects the deactivated admin
        val status =
            client()
                .get()
                .uri("/api/users/me")
                .accept(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer $inFlight")
                .exchange()
                .statusCode()

        assertThat(status).isEqualTo(403)
    }

    @Test
    fun `a rotated capability token no longer authenticates`() {
        val admin = seededUser("jane_doe")
        val max = seededUser(member)
        userService.rotateCapabilityToken(max.persistedId, admin)

        // the old (fixture) token is now invalid, so a request bearing it is unauthorized
        val status =
            client()
                .get()
                .uri("/api/summary")
                .withMember(member)
                .exchange()
                .statusCode()

        assertThat(status).isEqualTo(401)
    }
}
