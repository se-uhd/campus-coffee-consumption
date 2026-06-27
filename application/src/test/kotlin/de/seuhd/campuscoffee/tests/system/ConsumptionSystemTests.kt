package de.seuhd.campuscoffee.tests.system

import de.seuhd.campuscoffee.api.dtos.ConsumptionOverrideDto
import de.seuhd.campuscoffee.api.dtos.MAX_MONEY_CENTS
import de.seuhd.campuscoffee.api.dtos.PriceUpdateDto
import de.seuhd.campuscoffee.api.dtos.UserDto
import de.seuhd.campuscoffee.api.dtos.UserSummaryDto
import de.seuhd.campuscoffee.domain.model.Role
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/**
 * System tests for the user self-service consumption and profile flow, authenticated by the
 * `X-Capability-Token` header. A user adds a coffee (`POST /consumption`, no body) and undoes the most recent
 * one within the grace period (`POST /consumption/cancel`); reads come from `GET /summary`. The user is
 * the seeded `maxmustermann` fixture.
 */
class ConsumptionSystemTests : AbstractSystemTest() {
    private val member = "maxmustermann"

    private fun summary(): UserSummaryDto =
        client()
            .get()
            .uri("/api/summary")
            .accept(MediaType.APPLICATION_JSON)
            .withMember(member)
            .exchange()
            .returnResult<UserSummaryDto>()
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
    fun `getting the summary returns the count, price, and an empty activity before any coffee`() {
        val dto = summary()

        assertThat(dto.count).isEqualTo(0)
        assertThat(dto.priceCents).isEqualTo(50)
        assertThat(dto.activity).isEmpty()
    }

    @Test
    fun `adding a coffee returns 200 with the new count`() {
        val result = add().returnResult<UserSummaryDto>()

        assertThat(result.status.value()).isEqualTo(200)
        assertThat(result.responseBody!!.count).isEqualTo(1)
    }

    @Test
    fun `adding a coffee leaves the member owing the price of one cup`() {
        add()

        // a coffee at 50 cents leaves the user owing 50 cents (a negative balance)
        assertThat(summary().balanceCents).isEqualTo(-50)
    }

    @Test
    fun `cancelling within the grace period restores the count and balance to zero`() {
        add()
        assertThat(summary().count).isEqualTo(1)

        val result = cancel().returnResult<UserSummaryDto>()

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

        // the user cannot undo a coffee the admin already removed
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

        val cancelled = cancel().returnResult<UserSummaryDto>()
        assertThat(cancelled.status.value()).isEqualTo(200)

        // undoing the newest coffee credits exactly its 200, leaving only the first 50 owed
        assertThat(summary().balanceCents).isEqualTo(-50)
    }

    @Test
    fun `the activity shows the added coffee newest first`() {
        add()

        val activity = summary().activity
        assertThat(activity.first().count).isEqualTo(1)
        assertThat(activity.first().delta).isEqualTo(1)
        assertThat(activity.first().createdBy).isEqualTo(member)
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
        assertThat(profile.capabilityUrl).contains("/login/")
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

    @Test
    fun `setting a price above the money cap returns 400 Bad Request`() {
        // the @Max fat-finger guardrail rejects an absurd amount before the domain ever sees it
        assertThat(setPrice((MAX_MONEY_CENTS + 1).toInt()).statusCode()).isEqualTo(400)
    }

    @Test
    fun `concurrent self-scans never lose an update and surface any conflict as 409`() {
        val threads = 8
        val pool = Executors.newFixedThreadPool(threads)
        try {
            // align the starts so the writes overlap in the load-count -> append -> version-check window
            val start = CountDownLatch(1)
            val tasks =
                (1..threads).map {
                    pool.submit<Int> {
                        start.await()
                        add().statusCode()
                    }
                }
            start.countDown()
            val statuses = tasks.map { it.get() }

            // every concurrent add either succeeds (200) or loses the optimistic-lock race cleanly (409),
            // never a 500; and the final count equals exactly the number of successes (no lost update)
            assertThat(statuses).allMatch { it == 200 || it == 409 }
            val successes = statuses.count { it == 200 }
            assertThat(successes).isGreaterThanOrEqualTo(1)
            assertThat(summary().count).isEqualTo(successes)
        } finally {
            pool.shutdown()
        }
    }

    @Test
    fun `renaming a member's login name is ignored, so their undo and balance survive`() {
        // give the user history: one coffee, so the balance is negative and the cup is still cancellable
        add()
        val before = summary()
        assertThat(before.count).isEqualTo(1)
        assertThat(before.balanceCents).isLessThan(0)
        assertThat(before.cancellable).isTrue()

        // an admin tries to rename the user; the login name is immutable (pinned), so the change is dropped
        val target = seededUser(member)
        val response =
            client()
                .put()
                .uri("/api/users/{id}", target.persistedId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(
                    UserDto(
                        id = target.persistedId,
                        loginName = "renamed_max",
                        emailAddress = target.emailAddress,
                        firstName = target.firstName,
                        lastName = target.lastName,
                        role = Role.USER,
                        active = true
                    )
                ).withAdmin()
                .exchange()
                .returnResult<UserDto>()
                .responseBody!!
        assertThat(response.loginName).isEqualTo(member)

        // the activity classifies the user's own scan by the (unchanged) login, so undo and balance survive
        val after = summary()
        assertThat(after.count).isEqualTo(1)
        assertThat(after.balanceCents).isEqualTo(before.balanceCents)
        assertThat(after.cancellable).isTrue()
    }
}
