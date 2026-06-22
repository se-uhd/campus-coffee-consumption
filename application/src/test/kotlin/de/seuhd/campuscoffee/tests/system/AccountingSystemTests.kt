package de.seuhd.campuscoffee.tests.system

import de.seuhd.campuscoffee.api.dtos.AdjustmentRequestDto
import de.seuhd.campuscoffee.api.dtos.AdminExpenseDto
import de.seuhd.campuscoffee.api.dtos.ExpenseDto
import de.seuhd.campuscoffee.api.dtos.KittyDto
import de.seuhd.campuscoffee.api.dtos.MemberBalanceDto
import de.seuhd.campuscoffee.api.dtos.MemberExpenseDto
import de.seuhd.campuscoffee.api.dtos.MemberSummaryDto
import de.seuhd.campuscoffee.api.dtos.PaymentDto
import de.seuhd.campuscoffee.api.dtos.PriceChangeDto
import de.seuhd.campuscoffee.api.dtos.PriceDto
import de.seuhd.campuscoffee.api.dtos.PriceUpdateDto
import de.seuhd.campuscoffee.api.dtos.SettlementRequestDto
import de.seuhd.campuscoffee.domain.model.LedgerEntryType
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
 * System tests for the money feature: the global price, member and admin expenses, settlements and kitty
 * adjustments, the per-member balance (a coffee valued at the price in effect when it was consumed), and the
 * communal kitty. Members authenticate by their capability token; admins by JWT.
 */
class AccountingSystemTests : AbstractSystemTest() {
    private val member = "maxmustermann"

    private fun memberId(): UUID = seededUser(member).persistedId

    private fun setPrice(amountCents: Int) =
        client()
            .put()
            .uri("/api/price")
            .contentType(MediaType.APPLICATION_JSON)
            .body(PriceUpdateDto(amountCents))
            .withAdmin()
            .exchange()

    private fun addCoffee() =
        client()
            .post()
            .uri("/api/consumption")
            .withMember(member)
            .exchange()

    private fun memberSummary(): MemberSummaryDto =
        client()
            .get()
            .uri("/api/summary")
            .accept(MediaType.APPLICATION_JSON)
            .withMember(member)
            .exchange()
            .returnResult<MemberSummaryDto>()
            .responseBody!!

    private fun kitty(): KittyDto =
        client()
            .get()
            .uri("/api/kitty/ledger")
            .accept(MediaType.APPLICATION_JSON)
            .withAdmin()
            .exchange()
            .returnResult<KittyDto>()
            .responseBody!!

    // Funds the kitty with an admin adjustment so a later kitty-funded admin expense does not drive the
    // kitty balance below zero (the guard rejects that with 409). A pure kitty adjustment has no member,
    // so it never touches a member balance.
    private fun fundKitty(amountCents: Int) =
        client()
            .post()
            .uri("/api/payments/adjustment")
            .contentType(MediaType.APPLICATION_JSON)
            .body(AdjustmentRequestDto(amountCents = amountCents, note = "float"))
            .withAdmin()
            .exchange()

    @Test
    fun `each coffee is valued at the price in effect when it was consumed`() {
        // price 50, add a coffee; raise to 70, add a coffee -> balance owes 50 + 70 = 120 cents
        setPrice(50)
        addCoffee()
        setPrice(70)
        addCoffee()

        val summary = memberSummary()

        assertThat(summary.count).isEqualTo(2)
        assertThat(summary.priceCents).isEqualTo(70)
        assertThat(summary.balanceCents).isEqualTo(-120)
    }

    @Test
    fun `an admin reads the current price returns 200`() {
        val result =
            client()
                .get()
                .uri("/api/price")
                .accept(MediaType.APPLICATION_JSON)
                .withAdmin()
                .exchange()
                .returnResult<PriceDto>()

        assertThat(result.status.value()).isEqualTo(200)
        // the price seeded on startup (campus-coffee.price.initial-cents default)
        assertThat(result.responseBody!!.amountCents).isEqualTo(50)
    }

    @Test
    fun `setting the price returns it and records it in the history`() {
        val result =
            setPrice(80).returnResult<PriceDto>()
        assertThat(result.status.value()).isEqualTo(200)
        assertThat(result.responseBody!!.amountCents).isEqualTo(80)

        val history =
            client()
                .get()
                .uri("/api/price/history")
                .accept(MediaType.APPLICATION_JSON)
                .withAdmin()
                .exchange()
                .returnResult<Array<PriceChangeDto>>()
                .responseBody!!

        // newest first: the just-set 80, then the seeded 50
        assertThat(history.first().amountCents).isEqualTo(80)
        assertThat(history.map { it.amountCents }).contains(50, 80)
    }

    @Test
    fun `a member records a purchase and the balance is credited by the private amount`() {
        val summary =
            client()
                .post()
                .uri("/api/expenses")
                .contentType(MediaType.APPLICATION_JSON)
                .body(MemberExpenseDto(weightGrams = 1000, amountCents = 900, note = "beans"))
                .withMember(member)
                .exchange()
                .returnResult<MemberSummaryDto>()
                .responseBody!!

        // the purchase is 100% private, so the member's balance is credited by the full 900 cents
        assertThat(summary.balanceCents).isEqualTo(900)
        val privateEntries = summary.ledger.filter { it.type == LedgerEntryType.PRIVATE_EXPENSE }
        assertThat(privateEntries).hasSize(1)
        assertThat(privateEntries.first().amountCents).isEqualTo(900)
        // a member's own purchase never touches the kitty
        assertThat(summary.ledger.none { it.type == LedgerEntryType.KITTY_EXPENSE }).isTrue()
    }

    @Test
    fun `an admin split expense credits the member privately and draws the kitty down`() {
        // start the kitty with a float so the kitty-funded portion is visible against a known base
        client()
            .post()
            .uri("/api/payments/adjustment")
            .contentType(MediaType.APPLICATION_JSON)
            .body(AdjustmentRequestDto(amountCents = 1000, note = "float"))
            .withAdmin()
            .exchange()
        val kittyBefore = kitty().balanceCents

        val expense =
            client()
                .post()
                .uri("/api/users/{id}/expenses", memberId())
                .contentType(MediaType.APPLICATION_JSON)
                .body(
                    AdminExpenseDto(
                        weightGrams = 1000,
                        amountCents = 900,
                        privateAmountCents = 400,
                        kittyAmountCents = 500,
                        note = null
                    )
                ).withAdmin()
                .exchange()
                .returnResult<ExpenseDto>()
        assertThat(expense.status.value()).isEqualTo(201)
        assertThat(expense.responseBody!!.privateAmountCents).isEqualTo(400)

        // the member's ledger shows only the +400 private portion
        val summary = memberSummary()
        assertThat(summary.balanceCents).isEqualTo(400)
        assertThat(summary.ledger.filter { it.type == LedgerEntryType.PRIVATE_EXPENSE }.map { it.amountCents })
            .containsExactly(400)

        // the kitty drops by the 500 kitty-funded portion
        val kittyAfter = kitty()
        assertThat(kittyAfter.balanceCents).isEqualTo(kittyBefore - 500)
        assertThat(kittyAfter.entries.first { it.type == LedgerEntryType.KITTY_EXPENSE }.amountCents).isEqualTo(-500)
    }

    @Test
    fun `a settlement credits the member and feeds the kitty`() {
        val kittyBefore = kitty().balanceCents

        val payment =
            client()
                .post()
                .uri("/api/payments/settlement")
                .contentType(MediaType.APPLICATION_JSON)
                .body(SettlementRequestDto(userId = memberId(), amountCents = 1000, note = "paid"))
                .withAdmin()
                .exchange()
                .returnResult<PaymentDto>()
        assertThat(payment.status.value()).isEqualTo(201)

        assertThat(memberSummary().balanceCents).isEqualTo(1000)
        assertThat(kitty().balanceCents).isEqualTo(kittyBefore + 1000)
    }

    @Test
    fun `a kitty adjustment changes the kitty without changing a member balance`() {
        val kittyBefore = kitty().balanceCents

        client()
            .post()
            .uri("/api/payments/adjustment")
            .contentType(MediaType.APPLICATION_JSON)
            .body(AdjustmentRequestDto(amountCents = 750, note = "float"))
            .withAdmin()
            .exchange()

        assertThat(kitty().balanceCents).isEqualTo(kittyBefore + 750)
        // the member's balance is untouched by a pure kitty adjustment
        assertThat(memberSummary().balanceCents).isEqualTo(0)
    }

    @Test
    fun `correcting an expense with the same buyer returns 200`() {
        // fund the kitty so the kitty-funded portions (500, then 300) have somewhere to come from
        fundKitty(1000)

        val expenseId =
            client()
                .post()
                .uri("/api/users/{id}/expenses", memberId())
                .contentType(MediaType.APPLICATION_JSON)
                .body(
                    AdminExpenseDto(
                        weightGrams = 1000,
                        amountCents = 900,
                        privateAmountCents = 400,
                        kittyAmountCents = 500,
                        note = null
                    )
                ).withAdmin()
                .exchange()
                .returnResult<ExpenseDto>()
                .responseBody!!
                .id

        val corrected =
            client()
                .put()
                .uri("/api/users/{userId}/expenses/{expenseId}", memberId(), expenseId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(
                    AdminExpenseDto(
                        weightGrams = 1200,
                        amountCents = 1000,
                        privateAmountCents = 700,
                        kittyAmountCents = 300,
                        note = "corrected"
                    )
                ).withAdmin()
                .exchange()
                .returnResult<ExpenseDto>()
        assertThat(corrected.status.value()).isEqualTo(200)
        assertThat(corrected.responseBody!!.privateAmountCents).isEqualTo(700)
    }

    @Test
    fun `correcting an expense to a different buyer returns 400 Bad Request`() {
        // fund the kitty so recording the kitty-funded expense (500) succeeds
        fundKitty(1000)

        val expenseId =
            client()
                .post()
                .uri("/api/users/{id}/expenses", memberId())
                .contentType(MediaType.APPLICATION_JSON)
                .body(
                    AdminExpenseDto(
                        weightGrams = 1000,
                        amountCents = 900,
                        privateAmountCents = 400,
                        kittyAmountCents = 500,
                        note = null
                    )
                ).withAdmin()
                .exchange()
                .returnResult<ExpenseDto>()
                .responseBody!!
                .id

        // PUT the same expense under a different buyer in the path -> the buyer cannot be changed
        val otherUserId = seededUser("student2023").persistedId
        val status =
            client()
                .put()
                .uri("/api/users/{userId}/expenses/{expenseId}", otherUserId, expenseId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(
                    AdminExpenseDto(
                        weightGrams = 1000,
                        amountCents = 900,
                        privateAmountCents = 400,
                        kittyAmountCents = 500,
                        note = null
                    )
                ).withAdmin()
                .exchange()
                .statusCode()
        assertThat(status).isEqualTo(400)
    }

    @Test
    fun `an admin lists a member's expenses with their ids and amounts`() {
        // fund the kitty so the second expense's kitty-funded portion (500) has somewhere to come from
        fundKitty(1000)

        val first =
            client()
                .post()
                .uri("/api/users/{id}/expenses", memberId())
                .contentType(MediaType.APPLICATION_JSON)
                .body(
                    AdminExpenseDto(
                        weightGrams = 500,
                        amountCents = 300,
                        privateAmountCents = 300,
                        kittyAmountCents = 0,
                        note = null
                    )
                ).withAdmin()
                .exchange()
                .returnResult<ExpenseDto>()
                .responseBody!!
        val second =
            client()
                .post()
                .uri("/api/users/{id}/expenses", memberId())
                .contentType(MediaType.APPLICATION_JSON)
                .body(
                    AdminExpenseDto(
                        weightGrams = 1000,
                        amountCents = 900,
                        privateAmountCents = 400,
                        kittyAmountCents = 500,
                        note = null
                    )
                ).withAdmin()
                .exchange()
                .returnResult<ExpenseDto>()
                .responseBody!!

        val list =
            client()
                .get()
                .uri("/api/users/{id}/expenses", memberId())
                .accept(MediaType.APPLICATION_JSON)
                .withAdmin()
                .exchange()
                .returnResult<Array<ExpenseDto>>()
        assertThat(list.status.value()).isEqualTo(200)
        val expenses = list.responseBody!!.toList()
        assertThat(expenses.map { it.id }).containsExactlyInAnyOrder(first.id, second.id)
        assertThat(expenses.map { it.amountCents }).containsExactlyInAnyOrder(300, 900)
    }

    @Test
    fun `a member listing another member's expenses is forbidden with 403`() {
        val status =
            client()
                .get()
                .uri("/api/users/{id}/expenses", memberId())
                .accept(MediaType.APPLICATION_JSON)
                .withMember(member)
                .exchange()
                .statusCode()
        assertThat(status).isEqualTo(403)
    }

    @Test
    fun `recording an expense with a zero amount returns 400 Bad Request`() {
        val status =
            client()
                .post()
                .uri("/api/expenses")
                .contentType(MediaType.APPLICATION_JSON)
                .body(MemberExpenseDto(weightGrams = 1000, amountCents = 0, note = "beans"))
                .withMember(member)
                .exchange()
                .statusCode()
        assertThat(status).isEqualTo(400)
    }

    @Test
    fun `a fully kitty-funded admin expense leaves the buyer's balance unchanged`() {
        // float the kitty so the kitty draw has somewhere to come from
        client()
            .post()
            .uri("/api/payments/adjustment")
            .contentType(MediaType.APPLICATION_JSON)
            .body(AdjustmentRequestDto(amountCents = 1000, note = "float"))
            .withAdmin()
            .exchange()

        client()
            .post()
            .uri("/api/users/{id}/expenses", memberId())
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                AdminExpenseDto(
                    weightGrams = 1000,
                    amountCents = 900,
                    privateAmountCents = 0,
                    kittyAmountCents = 900,
                    note = null
                )
            ).withAdmin()
            .exchange()

        // with no private portion, the buyer's balance is untouched and no PRIVATE_EXPENSE row appears
        val summary = memberSummary()
        assertThat(summary.balanceCents).isEqualTo(0)
        assertThat(summary.ledger.none { it.type == LedgerEntryType.PRIVATE_EXPENSE }).isTrue()
    }

    @Test
    fun `the admin overview reports every member's count and balance`() {
        addCoffee()

        val overview =
            client()
                .get()
                .uri("/api/users/overview")
                .accept(MediaType.APPLICATION_JSON)
                .withAdmin()
                .exchange()
                .returnResult<Array<MemberBalanceDto>>()
                .responseBody!!

        val max = overview.first { it.loginName == member }
        assertThat(max.count).isEqualTo(1)
        assertThat(max.balanceCents).isEqualTo(-50)
    }

    @Test
    fun `an admin expense whose kitty portion exceeds the kitty balance returns 409 Conflict`() {
        // fund the kitty with only 300; an expense with a 500 kitty portion would overdraw it
        fundKitty(300)

        val status =
            client()
                .post()
                .uri("/api/users/{id}/expenses", memberId())
                .contentType(MediaType.APPLICATION_JSON)
                .body(
                    AdminExpenseDto(
                        weightGrams = 1000,
                        amountCents = 900,
                        privateAmountCents = 400,
                        kittyAmountCents = 500,
                        note = null
                    )
                ).withAdmin()
                .exchange()
                .statusCode()
        assertThat(status).isEqualTo(409)
    }

    @Test
    fun `a kitty adjustment that would overdraw the kitty returns 409 Conflict`() {
        // fund the kitty with 500; a -800 adjustment would drive the balance below zero
        fundKitty(500)

        val status =
            client()
                .post()
                .uri("/api/payments/adjustment")
                .contentType(MediaType.APPLICATION_JSON)
                .body(AdjustmentRequestDto(amountCents = -800, note = "overdraw"))
                .withAdmin()
                .exchange()
                .statusCode()
        assertThat(status).isEqualTo(409)
    }
}
