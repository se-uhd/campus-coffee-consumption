package de.seuhd.campuscoffee.tests.system

import de.seuhd.campuscoffee.api.dtos.ActivityEntryDto
import de.seuhd.campuscoffee.api.dtos.AdjustmentRequestDto
import de.seuhd.campuscoffee.api.dtos.AdminExpenseDto
import de.seuhd.campuscoffee.api.dtos.ConsumptionOverrideDto
import de.seuhd.campuscoffee.api.dtos.DepositRequestDto
import de.seuhd.campuscoffee.api.dtos.ExpenseDto
import de.seuhd.campuscoffee.api.dtos.KittyDto
import de.seuhd.campuscoffee.api.dtos.UserSummaryDto
import de.seuhd.campuscoffee.domain.model.ActivityEntryType
import de.seuhd.campuscoffee.domain.model.persistedId
import de.seuhd.campuscoffee.tests.SystemTestUtils.client
import de.seuhd.campuscoffee.tests.SystemTestUtils.statusCode
import de.seuhd.campuscoffee.tests.SystemTestUtils.withAdmin
import de.seuhd.campuscoffee.tests.SystemTestUtils.withUser
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.client.returnResult
import java.util.UUID

/**
 * System tests for the activity and kitty projections under correction and deletion: an admin correcting and
 * deleting an expense, a deposit and a kitty adjustment, and an admin count override. These exercise the
 * UPDATE/DELETE and admin-override branches of the user-activity and kitty-history walks.
 */
class AccountingActivitySystemTests : AbstractSystemTest() {
    private val user = "maxmustermann"

    private fun userId(): UUID = seededUser(user).persistedId

    private fun adminExpense(
        amountCents: Int,
        privateAmountCents: Int,
        kittyAmountCents: Int
    ) = client()
        .post()
        .uri("/api/users/{id}/expenses", userId())
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            AdminExpenseDto(
                weightGrams = 1000,
                amountCents = amountCents,
                privateAmountCents = privateAmountCents,
                kittyAmountCents = kittyAmountCents,
                note = null
            )
        ).withAdmin()
        .exchange()

    // Funds the kitty with an admin adjustment so a later kitty-funded admin expense does not drive the
    // kitty balance below zero (the guard rejects that with 409). A pure kitty adjustment has no user,
    // so it never touches a user balance.
    private fun fundKitty(amountCents: Int) =
        client()
            .post()
            .uri("/api/kitty/adjustment")
            .contentType(MediaType.APPLICATION_JSON)
            .body(AdjustmentRequestDto(amountCents = amountCents, note = "float"))
            .withAdmin()
            .exchange()

    private fun userSummary(): UserSummaryDto =
        client()
            .get()
            .uri("/api/summary")
            .accept(MediaType.APPLICATION_JSON)
            .withUser(user)
            .exchange()
            .returnResult<UserSummaryDto>()
            .responseBody!!

    private fun kitty(): KittyDto =
        client()
            .get()
            .uri("/api/kitty/history")
            .accept(MediaType.APPLICATION_JSON)
            .withAdmin()
            .exchange()
            .returnResult<KittyDto>()
            .responseBody!!

    private fun adminUserActivity(): List<ActivityEntryDto> =
        client()
            .get()
            .uri("/api/users/{id}/activity", userId())
            .accept(MediaType.APPLICATION_JSON)
            .withAdmin()
            .exchange()
            .returnResult<Array<ActivityEntryDto>>()
            .responseBody!!
            .toList()

    private fun ownUserActivity(): List<ActivityEntryDto> =
        client()
            .get()
            .uri("/api/activity")
            .accept(MediaType.APPLICATION_JSON)
            .withUser(user)
            .exchange()
            .returnResult<Array<ActivityEntryDto>>()
            .responseBody!!
            .toList()

    @ParameterizedTest
    @ValueSource(strings = ["limit=101", "limit=0", "offset=-1"])
    fun `a paged read with an out-of-range PageQuery returns 400 Bad Request`(query: String) {
        // The paged reads share the PageQuery object, validated via @Valid binding (no class-level
        // @Validated): @Max(100) and @Positive on the limit and @Min(0) on the offset all surface as a 400.
        val status =
            client()
                .get()
                .uri("/api/users/{id}/activity?$query", userId())
                .accept(MediaType.APPLICATION_JSON)
                .withAdmin()
                .exchange()
                .statusCode()
        assertThat(status).isEqualTo(400)
    }

    @Test
    fun `a deposit with no user returns 400 Bad Request`() {
        val status =
            client()
                .post()
                .uri("/api/kitty/deposit")
                .contentType(MediaType.APPLICATION_JSON)
                .body(DepositRequestDto(userId = null, amountCents = 500, note = null))
                .withAdmin()
                .exchange()
                .statusCode()
        assertThat(status).isEqualTo(400)
    }

    @ParameterizedTest
    @ValueSource(ints = [0, -1])
    fun `a deposit with a non-positive amount returns 400 Bad Request`(amountCents: Int) {
        val status =
            client()
                .post()
                .uri("/api/kitty/deposit")
                .contentType(MediaType.APPLICATION_JSON)
                .body(DepositRequestDto(userId = userId(), amountCents = amountCents, note = null))
                .withAdmin()
                .exchange()
                .statusCode()
        assertThat(status).isEqualTo(400)
    }

    @Test
    fun `a kitty adjustment of zero returns 400 Bad Request`() {
        val status =
            client()
                .post()
                .uri("/api/kitty/adjustment")
                .contentType(MediaType.APPLICATION_JSON)
                .body(AdjustmentRequestDto(amountCents = 0, note = null))
                .withAdmin()
                .exchange()
                .statusCode()
        assertThat(status).isEqualTo(400)
    }

    @Test
    fun `correcting an expense re-credits the user by the new private portion`() {
        // fund the kitty with a 1000 float so the kitty-funded portions (500, then 300) stay non-negative
        fundKitty(1000)

        val expenseId =
            adminExpense(amountCents = 900, privateAmountCents = 400, kittyAmountCents = 500)
                .returnResult<ExpenseDto>()
                .responseBody!!
                .id
        assertThat(userSummary().balanceCents).isEqualTo(400)

        // correct it to a larger private portion: 1000 = 700 private + 300 kitty
        client()
            .put()
            .uri("/api/users/{userId}/expenses/{expenseId}", userId(), expenseId)
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                AdminExpenseDto(
                    weightGrams = 800,
                    amountCents = 1000,
                    privateAmountCents = 700,
                    kittyAmountCents = 300,
                    note = "corrected"
                )
            ).withAdmin()
            .exchange()

        // the user's balance reflects the corrected private portion (the full state, not a double count)
        assertThat(userSummary().balanceCents).isEqualTo(700)
        // the kitty reflects the corrected kitty portion (1000 float - 300, not - 500)
        assertThat(kitty().balanceCents).isEqualTo(700)
    }

    @Test
    fun `deleting an expense returns 204 and reverses its effect on the balance and kitty`() {
        // fund the kitty with a 1000 float so the kitty-funded portion (500) stays non-negative
        fundKitty(1000)

        val expenseId =
            adminExpense(amountCents = 900, privateAmountCents = 400, kittyAmountCents = 500)
                .returnResult<ExpenseDto>()
                .responseBody!!
                .id
        assertThat(userSummary().balanceCents).isEqualTo(400)
        // the float (1000) minus the kitty-funded portion (500)
        assertThat(kitty().balanceCents).isEqualTo(500)

        val deleteStatus =
            client()
                .delete()
                .uri("/api/users/{userId}/expenses/{expenseId}", userId(), expenseId)
                .withAdmin()
                .exchange()
                .statusCode()
        assertThat(deleteStatus).isEqualTo(204)

        // the DELETE event carries the buyer id, so both the user activity and the kitty reverse it:
        // the user balance returns to 0 and the kitty returns to the float (1000)
        assertThat(userSummary().balanceCents).isEqualTo(0)
        assertThat(kitty().balanceCents).isEqualTo(1000)
    }

    @Test
    fun `a deposit appears on the user activity as a DEPOSIT entry`() {
        client()
            .post()
            .uri("/api/kitty/deposit")
            .contentType(MediaType.APPLICATION_JSON)
            .body(DepositRequestDto(userId = userId(), amountCents = 1000, note = "paid"))
            .withAdmin()
            .exchange()

        val activity = adminUserActivity()
        assertThat(activity.filter { it.type == ActivityEntryType.DEPOSIT }.map { it.amountCents })
            .containsExactly(1000)
    }

    @Test
    fun `a negative kitty adjustment lowers the kitty balance`() {
        client()
            .post()
            .uri("/api/kitty/adjustment")
            .contentType(MediaType.APPLICATION_JSON)
            .body(AdjustmentRequestDto(amountCents = 1000, note = "float"))
            .withAdmin()
            .exchange()
        client()
            .post()
            .uri("/api/kitty/adjustment")
            .contentType(MediaType.APPLICATION_JSON)
            .body(AdjustmentRequestDto(amountCents = -250, note = "correction"))
            .withAdmin()
            .exchange()

        assertThat(kitty().balanceCents).isEqualTo(750)
        assertThat(kitty().entries.map { it.type }).contains(ActivityEntryType.KITTY_ADJUSTMENT)
    }

    @Test
    fun `an admin count override is valued as a lump on the user activity`() {
        // the price is the seeded 50; an admin sets the count to 3 in one step -> a -150 lump
        client()
            .put()
            .uri("/api/users/{id}/consumption", userId())
            .contentType(MediaType.APPLICATION_JSON)
            .body(ConsumptionOverrideDto(3, "manual"))
            .withAdmin()
            .exchange()

        val summary = userSummary()
        assertThat(summary.count).isEqualTo(3)
        assertThat(summary.balanceCents).isEqualTo(-150)
        assertThat(summary.activity.first { it.type == ActivityEntryType.CONSUMPTION }.amountCents).isEqualTo(-150)
    }

    @Test
    fun `a user buys then an admin attributes a split purchase building the full activity`() {
        // fund the kitty so the admin split purchase's kitty-funded portion (500) stays non-negative;
        // a pure kitty adjustment has no user, so the user balance assertion below is unaffected
        fundKitty(1000)

        // a user's own purchase (100% private), an admin split purchase, a coffee, and a deposit
        client()
            .post()
            .uri("/api/expenses")
            .contentType(MediaType.APPLICATION_JSON)
            .body(mapOf("weightGrams" to 500, "amountCents" to 300))
            .withUser(user)
            .exchange()
        adminExpense(amountCents = 900, privateAmountCents = 400, kittyAmountCents = 500)
        client()
            .post()
            .uri("/api/consumption")
            .withUser(user)
            .exchange()
        client()
            .post()
            .uri("/api/kitty/deposit")
            .contentType(MediaType.APPLICATION_JSON)
            .body(DepositRequestDto(userId = userId(), amountCents = 1000, note = null))
            .withAdmin()
            .exchange()

        // 300 + 400 - 50 + 1000 = 1650
        assertThat(userSummary().balanceCents).isEqualTo(1650)
        val types = adminUserActivity().map { it.type }.toSet()
        assertThat(types).contains(
            ActivityEntryType.PRIVATE_EXPENSE,
            ActivityEntryType.CONSUMPTION,
            ActivityEntryType.DEPOSIT
        )
    }

    @Test
    fun `an admin sees the kitty split on a user's expense but the user's own activity does not`() {
        // fund the kitty so the split purchase's kitty-funded portion (500) stays non-negative
        fundKitty(1000)
        // an admin records a split bean purchase on the user: 900 = 400 private + 500 kitty
        adminExpense(amountCents = 900, privateAmountCents = 400, kittyAmountCents = 500)

        // the admin-by-id activity breaks the split out: the private portion is the balance effect, and both
        // portions are carried alongside on the PRIVATE_EXPENSE entry
        val adminEntry = adminUserActivity().first { it.type == ActivityEntryType.PRIVATE_EXPENSE }
        assertThat(adminEntry.amountCents).isEqualTo(400)
        assertThat(adminEntry.privateAmountCents).isEqualTo(400)
        assertThat(adminEntry.kittyAmountCents).isEqualTo(500)

        // the admin kitty history's KITTY_EXPENSE entry carries the same split breakdown (so the kitty history
        // can render the identical `private + kitty` footer); its own effect is the negative kitty draw
        val kittyEntry = kitty().entries.first { it.type == ActivityEntryType.KITTY_EXPENSE }
        assertThat(kittyEntry.amountCents).isEqualTo(-500)
        assertThat(kittyEntry.privateAmountCents).isEqualTo(400)
        assertThat(kittyEntry.kittyAmountCents).isEqualTo(500)

        // the SAME user's own activity never exposes the kitty split (it is admin-only): the entry is still
        // there with the private amount, but both split portions are null
        val ownEntry = ownUserActivity().first { it.type == ActivityEntryType.PRIVATE_EXPENSE }
        assertThat(ownEntry.amountCents).isEqualTo(400)
        assertThat(ownEntry.privateAmountCents).isNull()
        assertThat(ownEntry.kittyAmountCents).isNull()

        // and the user's summary activity (the landing-page view) likewise strips it
        val summaryEntry = userSummary().activity.first { it.type == ActivityEntryType.PRIVATE_EXPENSE }
        assertThat(summaryEntry.privateAmountCents).isNull()
        assertThat(summaryEntry.kittyAmountCents).isNull()
    }
}
