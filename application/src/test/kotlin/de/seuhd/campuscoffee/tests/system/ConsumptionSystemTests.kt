package de.seuhd.campuscoffee.tests.system

import de.seuhd.campuscoffee.api.dtos.ConsumptionOverrideDto
import de.seuhd.campuscoffee.api.dtos.MemberSummaryDto
import de.seuhd.campuscoffee.api.dtos.PriceUpdateDto
import de.seuhd.campuscoffee.api.dtos.UserDto
import de.seuhd.campuscoffee.domain.model.persistedId
import de.seuhd.campuscoffee.tests.SystemTestUtils.client
import de.seuhd.campuscoffee.tests.SystemTestUtils.statusCode
import de.seuhd.campuscoffee.tests.SystemTestUtils.withAdmin
import de.seuhd.campuscoffee.tests.SystemTestUtils.withMember
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.client.returnResult
import java.util.UUID

/**
 * System tests for the member self-service consumption and profile flow, authenticated by the
 * `X-Coffee-Token` header. A member adds a coffee (`POST /consumption`, no body) and undoes the most recent
 * one within the grace period (`POST /consumption/cancel`); reads come from `GET /summary`. The member is
 * the seeded `maxmustermann` fixture.
 */
class ConsumptionSystemTests : AbstractSystemTest() {
    private val member = "maxmustermann"

    private fun summary(): MemberSummaryDto =
        client()
            .get()
            .uri("/api/summary")
            .accept(MediaType.APPLICATION_JSON)
            .withMember(member)
            .exchange()
            .returnResult<MemberSummaryDto>()
            .responseBody!!

    private fun add() =
        client()
            .post()
            .uri("/api/consumption")
            .withMember(member)
            .exchange()

    private fun cancel() =
        client()
            .post()
            .uri("/api/consumption/cancel")
            .withMember(member)
            .exchange()

    private fun memberId(): UUID = seededUser(member).persistedId

    private fun overrideCount(total: Int) =
        client()
            .put()
            .uri("/api/users/{id}/consumption", memberId())
            .contentType(MediaType.APPLICATION_JSON)
            .body(ConsumptionOverrideDto(total, "manual"))
            .withAdmin()
            .exchange()

    private fun setPrice(amountCents: Int) =
        client()
            .put()
            .uri("/api/price")
            .contentType(MediaType.APPLICATION_JSON)
            .body(PriceUpdateDto(amountCents))
            .withAdmin()
            .exchange()

    @Test
    fun `getting the summary returns the count, price, and an empty ledger before any coffee`() {
        val dto = summary()

        assertThat(dto.count).isEqualTo(0)
        assertThat(dto.priceCents).isEqualTo(50)
        assertThat(dto.ledger).isEmpty()
    }

    @Test
    fun `adding a coffee returns 200 with the new count`() {
        val result = add().returnResult<MemberSummaryDto>()

        assertThat(result.status.value()).isEqualTo(200)
        assertThat(result.responseBody!!.count).isEqualTo(1)
    }

    @Test
    fun `adding a coffee leaves the member owing the price of one cup`() {
        add()

        // a coffee at 50 cents leaves the member owing 50 cents (a negative balance)
        assertThat(summary().balanceCents).isEqualTo(-50)
    }

    @Test
    fun `cancelling within the grace period restores the count and balance to zero`() {
        add()
        assertThat(summary().count).isEqualTo(1)

        val result = cancel().returnResult<MemberSummaryDto>()

        assertThat(result.status.value()).isEqualTo(200)
        assertThat(result.responseBody!!.count).isEqualTo(0)
        assertThat(summary().balanceCents).isEqualTo(0)
    }

    @Test
    fun `cancelling with nothing to undo returns 409 Conflict`() {
        assertThat(cancel().statusCode()).isEqualTo(409)
    }

    @Test
    fun `an admin override that lowers the count makes the summary not cancellable`() {
        add()
        assertThat(summary().cancellable).isTrue()

        // the admin removes the coffee with an absolute override to zero
        overrideCount(0)

        // the count gate alone makes a zero count uncancellable; the override also trimmed the undo stack
        val dto = summary()
        assertThat(dto.count).isEqualTo(0)
        assertThat(dto.cancellable).isFalse()
    }

    @Test
    fun `cancelling after an admin override removed the coffee returns 409 Conflict`() {
        add()
        overrideCount(0)

        // the member cannot undo a coffee the admin already removed
        assertThat(cancel().statusCode()).isEqualTo(409)
    }

    @Test
    fun `undo credits the most-recent increment's price after a price change`() {
        // add at price 50, raise the price to 200, add again -> the newest increment is valued at 200
        setPrice(50)
        add()
        setPrice(200)
        add()
        // owes 50 + 200 = 250
        assertThat(summary().balanceCents).isEqualTo(-250)

        val cancelled = cancel().returnResult<MemberSummaryDto>()
        assertThat(cancelled.status.value()).isEqualTo(200)

        // undoing the newest coffee credits exactly its 200, leaving only the first 50 owed
        assertThat(summary().balanceCents).isEqualTo(-50)
    }

    @Test
    fun `the ledger shows the added coffee newest first`() {
        add()

        val ledger = summary().ledger
        assertThat(ledger.first().count).isEqualTo(1)
        assertThat(ledger.first().delta).isEqualTo(1)
        assertThat(ledger.first().createdBy).isEqualTo(member)
    }

    @Test
    fun `getting own profile returns the capability URL`() {
        val profile =
            client()
                .get()
                .uri("/api/profile")
                .accept(MediaType.APPLICATION_JSON)
                .withMember(member)
                .exchange()
                .returnResult<UserDto>()
                .responseBody!!

        assertThat(profile.loginName).isEqualTo(member)
        assertThat(profile.capabilityUrl).contains("/coffee/")
    }

    @Test
    fun `downloading own QR code returns a PNG`() {
        val result =
            client()
                .get()
                .uri("/api/profile/qr.png")
                .withMember(member)
                .exchange()
                .returnResult<ByteArray>()

        assertThat(result.status.value()).isEqualTo(200)
        assertThat(result.responseHeaders.contentType.toString()).isEqualTo(MediaType.IMAGE_PNG_VALUE)
    }
}
