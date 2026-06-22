package de.seuhd.campuscoffee.tests.system

import de.seuhd.campuscoffee.api.dtos.SettlementRequestDto
import de.seuhd.campuscoffee.api.dtos.UserDto
import de.seuhd.campuscoffee.domain.model.persistedId
import de.seuhd.campuscoffee.tests.SystemTestUtils.client
import de.seuhd.campuscoffee.tests.SystemTestUtils.statusCode
import de.seuhd.campuscoffee.tests.SystemTestUtils.withAdmin
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.client.returnResult
import java.util.UUID

/**
 * System tests for the member deletion guard: a member with any financial footprint (a non-zero count or a
 * settlement) cannot be hard-deleted (409), preserving the financial history, while a pristine member is
 * deleted (204).
 */
class MemberDeletionSystemTests : AbstractSystemTest() {
    private fun createMember(loginName: String): UUID {
        val body =
            mapOf(
                "loginName" to loginName,
                "emailAddress" to "$loginName@se.de",
                "firstName" to "New",
                "lastName" to "Member",
                "role" to "USER"
            )
        return client()
            .post()
            .uri("/api/users")
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .withAdmin()
            .exchange()
            .returnResult<UserDto>()
            .responseBody!!
            .persistedId
    }

    private fun deleteStatus(id: UUID): Int =
        client()
            .delete()
            .uri("/api/users/{id}", id)
            .withAdmin()
            .exchange()
            .statusCode()

    @Test
    fun `deleting a pristine member returns 204`() {
        val id = createMember("pristine")

        assertThat(deleteStatus(id)).isEqualTo(204)
    }

    @Test
    fun `deleting a member who has consumed returns 409 Conflict`() {
        val id = createMember("hasconsumed")
        client()
            .post()
            .uri("/api/users/{id}/consumption", id)
            .contentType(MediaType.APPLICATION_JSON)
            .body(mapOf("delta" to 1))
            .withAdmin()
            .exchange()

        assertThat(deleteStatus(id)).isEqualTo(409)
    }

    @Test
    fun `deleting a member who has an expense returns 409 Conflict`() {
        val id = createMember("hasexpense")
        // record a fully-private bean purchase attributed to the member as buyer (their own purchase): the
        // member now has a financial footprint, so the hard delete is refused
        client()
            .post()
            .uri("/api/users/{id}/expenses", id)
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                mapOf(
                    "weightGrams" to 1000,
                    "amountCents" to 900,
                    "privateAmountCents" to 900,
                    "kittyAmountCents" to 0,
                    "note" to "own beans"
                )
            ).withAdmin()
            .exchange()

        assertThat(deleteStatus(id)).isEqualTo(409)
    }

    @Test
    fun `deleting a member who has a settlement returns 409 Conflict`() {
        val id = createMember("hassettlement")
        client()
            .post()
            .uri("/api/payments/settlement")
            .contentType(MediaType.APPLICATION_JSON)
            .body(SettlementRequestDto(userId = id, amountCents = 500, note = null))
            .withAdmin()
            .exchange()

        assertThat(deleteStatus(id)).isEqualTo(409)
    }
}
