package de.seuhd.campuscoffee.tests.system

import de.seuhd.campuscoffee.api.dtos.TokenRequestDto
import de.seuhd.campuscoffee.api.dtos.UserDto
import de.seuhd.campuscoffee.domain.model.Role
import de.seuhd.campuscoffee.domain.model.User
import de.seuhd.campuscoffee.domain.model.persistedId
import de.seuhd.campuscoffee.domain.tests.TestFixtures
import de.seuhd.campuscoffee.tests.SystemTestUtils.CAPABILITY_TOKEN_HEADER
import de.seuhd.campuscoffee.tests.SystemTestUtils.client
import de.seuhd.campuscoffee.tests.SystemTestUtils.jwtFor
import de.seuhd.campuscoffee.tests.SystemTestUtils.statusCode
import de.seuhd.campuscoffee.tests.SystemTestUtils.withMember
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType

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
        // deactivate the seeded admin, then try to log in as them
        userService.update(seededAdmin.copy(active = false), secondAdmin)

        val status =
            client()
                .post()
                .uri("/api/auth/token")
                .contentType(MediaType.APPLICATION_JSON)
                .body(TokenRequestDto(login, password))
                .exchange()
                .statusCode()

        // a disabled account is refused at login (DisabledException -> 401 via the global handler)
        assertThat(status).isEqualTo(401)
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
