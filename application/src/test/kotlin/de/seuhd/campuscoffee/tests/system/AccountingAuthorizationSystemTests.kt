package de.seuhd.campuscoffee.tests.system

import de.seuhd.campuscoffee.api.dtos.PriceUpdateDto
import de.seuhd.campuscoffee.api.dtos.UserSummaryDto
import de.seuhd.campuscoffee.tests.SystemTestUtils.client
import de.seuhd.campuscoffee.tests.SystemTestUtils.statusCode
import de.seuhd.campuscoffee.tests.SystemTestUtils.withMember
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.client.returnResult

/**
 * System tests for the money-feature authorization split: a user may read their own landing summary (which
 * includes the kitty balance) but may not set the price or reach the admin-only kitty history and expense
 * routes.
 */
class AccountingAuthorizationSystemTests : AbstractSystemTest() {
    private val member = "maxmustermann"

    @Test
    fun `a member cannot set the price and is forbidden`() {
        val status =
            client()
                .put()
                .uri("/api/price")
                .contentType(MediaType.APPLICATION_JSON)
                .body(PriceUpdateDto(70))
                .withMember(member)
                .exchange()
                .statusCode()

        assertThat(status).isEqualTo(403)
    }

    @Test
    fun `a member cannot read the kitty history and is forbidden`() {
        val status =
            client()
                .get()
                .uri("/api/kitty/history")
                .accept(MediaType.APPLICATION_JSON)
                .withMember(member)
                .exchange()
                .statusCode()

        assertThat(status).isEqualTo(403)
    }

    @Test
    fun `a member can read their own summary including the kitty balance`() {
        val result =
            client()
                .get()
                .uri("/api/summary")
                .accept(MediaType.APPLICATION_JSON)
                .withMember(member)
                .exchange()
                .returnResult<UserSummaryDto>()

        assertThat(result.status.value()).isEqualTo(200)
        // the kitty balance is present (zero with no money movements yet), readable by the user
        assertThat(result.responseBody!!.kittyBalanceCents).isEqualTo(0)
    }
}
