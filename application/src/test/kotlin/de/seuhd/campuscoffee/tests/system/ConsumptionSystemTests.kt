package de.seuhd.campuscoffee.tests.system

import de.seuhd.campuscoffee.api.dtos.ConsumptionDeltaDto
import de.seuhd.campuscoffee.api.dtos.ConsumptionDto
import de.seuhd.campuscoffee.api.dtos.UserDto
import de.seuhd.campuscoffee.tests.SystemTestUtils.client
import de.seuhd.campuscoffee.tests.SystemTestUtils.statusCode
import de.seuhd.campuscoffee.tests.SystemTestUtils.withMember
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.client.returnResult

/**
 * System tests for the member self-service consumption and profile flow, authenticated by the
 * `X-Coffee-Token` header. The member is the seeded `maxmustermann` fixture.
 */
class ConsumptionSystemTests : AbstractSystemTest() {
    private val member = "maxmustermann"

    private fun consumption(): ConsumptionDto =
        client()
            .get()
            .uri("/api/consumption")
            .accept(MediaType.APPLICATION_JSON)
            .withMember(member)
            .exchange()
            .returnResult<ConsumptionDto>()
            .responseBody!!

    private fun change(delta: Int) =
        client()
            .post()
            .uri("/api/consumption")
            .contentType(MediaType.APPLICATION_JSON)
            .body(ConsumptionDeltaDto(delta))
            .withMember(member)
            .exchange()

    @Test
    fun `getting own consumption returns the current total and the change log`() {
        val dto = consumption()

        assertThat(dto.total).isEqualTo(0)
        // the consumption was created at zero, so the log holds its initial insert
        assertThat(dto.changes).isNotEmpty()
        assertThat(dto.changes.first().count).isEqualTo(0)
    }

    @Test
    fun `incrementing the count returns 200 with the new total`() {
        val result = change(1).returnResult<ConsumptionDto>()

        assertThat(result.status.value()).isEqualTo(200)
        assertThat(result.responseBody!!.total).isEqualTo(1)
    }

    @Test
    fun `decrementing at zero returns 409 Conflict`() {
        assertThat(change(-1).statusCode()).isEqualTo(409)
    }

    @Test
    fun `a delta other than plus or minus one returns 400 Bad Request`() {
        assertThat(change(2).statusCode()).isEqualTo(400)
    }

    @Test
    fun `the change log shows the increment newest first`() {
        change(1)

        val changes = consumption().changes
        assertThat(changes.first().count).isEqualTo(1)
        assertThat(changes.first().delta).isEqualTo(1)
        assertThat(changes.first().createdBy).isEqualTo(member)
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
