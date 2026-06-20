package de.seuhd.campuscoffee.tests.system

import de.seuhd.campuscoffee.api.dtos.ConsumptionDeltaDto
import de.seuhd.campuscoffee.domain.model.persistedId
import de.seuhd.campuscoffee.tests.SystemTestUtils.COFFEE_TOKEN_HEADER
import de.seuhd.campuscoffee.tests.SystemTestUtils.client
import de.seuhd.campuscoffee.tests.SystemTestUtils.statusCode
import de.seuhd.campuscoffee.tests.SystemTestUtils.withMember
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType

/**
 * System tests for the authentication and authorization rules of the two mechanisms: a member capability
 * token and the admin JWT.
 */
class AuthorizationSystemTests : AbstractSystemTest() {
    private val member = "maxmustermann"

    @Test
    fun `a request with no credentials returns 401 Unauthorized`() {
        val status =
            client()
                .get()
                .uri("/api/consumption")
                .exchange()
                .statusCode()

        assertThat(status).isEqualTo(401)
    }

    @Test
    fun `an unknown capability token returns 401 Unauthorized`() {
        val status =
            client()
                .get()
                .uri("/api/consumption")
                .header(COFFEE_TOKEN_HEADER, "not-a-real-token")
                .exchange()
                .statusCode()

        assertThat(status).isEqualTo(401)
    }

    @Test
    fun `a deactivated member is read-only and cannot change their count`() {
        val admin = seededUser("jane_doe")
        val max = seededUser(member)
        userService.update(max.copy(active = false), admin)

        // the deactivated member still authenticates (a read works) but the mutation is forbidden
        val readStatus =
            client()
                .get()
                .uri("/api/consumption")
                .withMember(member)
                .exchange()
                .statusCode()
        val writeStatus =
            client()
                .post()
                .uri("/api/consumption")
                .contentType(MediaType.APPLICATION_JSON)
                .body(ConsumptionDeltaDto(1))
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
    fun `a rotated capability token no longer authenticates`() {
        val admin = seededUser("jane_doe")
        val max = seededUser(member)
        userService.rotateCapabilityToken(max.persistedId, admin)

        // the old (fixture) token is now invalid, so a request bearing it is unauthorized
        val status =
            client()
                .get()
                .uri("/api/consumption")
                .withMember(member)
                .exchange()
                .statusCode()

        assertThat(status).isEqualTo(401)
    }
}
