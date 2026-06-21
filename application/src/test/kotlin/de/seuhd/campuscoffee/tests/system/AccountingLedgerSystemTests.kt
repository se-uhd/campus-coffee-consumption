package de.seuhd.campuscoffee.tests.system

import de.seuhd.campuscoffee.api.dtos.AdjustmentRequestDto
import de.seuhd.campuscoffee.api.dtos.AdminExpenseDto
import de.seuhd.campuscoffee.api.dtos.ConsumptionOverrideDto
import de.seuhd.campuscoffee.api.dtos.ExpenseDto
import de.seuhd.campuscoffee.api.dtos.KittyDto
import de.seuhd.campuscoffee.api.dtos.LedgerEntryDto
import de.seuhd.campuscoffee.api.dtos.MemberSummaryDto
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
 * System tests for the ledger and kitty projections under correction and deletion: an admin correcting and
 * deleting an expense, a settlement and a kitty adjustment, and an admin count override. These exercise the
 * UPDATE/DELETE and admin-override branches of the member and kitty ledger walks.
 */
class AccountingLedgerSystemTests : AbstractSystemTest() {
    private val member = "maxmustermann"

    private fun memberId(): UUID = seededUser(member).persistedId

    private fun adminExpense(
        amountCents: Int,
        privateAmountCents: Int,
        kittyAmountCents: Int
    ) = client()
        .post()
        .uri("/api/users/{id}/expenses", memberId())
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

    private fun adminMemberLedger(): List<LedgerEntryDto> =
        client()
            .get()
            .uri("/api/users/{id}/ledger", memberId())
            .accept(MediaType.APPLICATION_JSON)
            .withAdmin()
            .exchange()
            .returnResult<Array<LedgerEntryDto>>()
            .responseBody!!
            .toList()

    @Test
    fun `correcting an expense re-credits the member by the new private portion`() {
        val expenseId =
            adminExpense(amountCents = 900, privateAmountCents = 400, kittyAmountCents = 500)
                .returnResult<ExpenseDto>()
                .responseBody!!
                .id
        assertThat(memberSummary().balanceCents).isEqualTo(400)

        // correct it to a larger private portion: 1000 = 700 private + 300 kitty
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

        // the member's balance reflects the corrected private portion (the full state, not a double count)
        assertThat(memberSummary().balanceCents).isEqualTo(700)
        // the kitty reflects the corrected kitty portion (-300, not -500)
        assertThat(kitty().balanceCents).isEqualTo(-300)
    }

    @Test
    fun `deleting an expense returns 204 and reverses its effect on the balance and kitty`() {
        val expenseId =
            adminExpense(amountCents = 900, privateAmountCents = 400, kittyAmountCents = 500)
                .returnResult<ExpenseDto>()
                .responseBody!!
                .id
        assertThat(memberSummary().balanceCents).isEqualTo(400)
        assertThat(kitty().balanceCents).isEqualTo(-500)

        val deleteStatus =
            client()
                .delete()
                .uri("/api/users/{userId}/expenses/{expenseId}", memberId(), expenseId)
                .withAdmin()
                .exchange()
                .statusCode()
        assertThat(deleteStatus).isEqualTo(204)

        // the DELETE event carries the buyer id, so both the member ledger and the kitty reverse it
        assertThat(memberSummary().balanceCents).isEqualTo(0)
        assertThat(kitty().balanceCents).isEqualTo(0)
    }

    @Test
    fun `a settlement appears on the member ledger as a SETTLEMENT entry`() {
        client()
            .post()
            .uri("/api/payments/settlement")
            .contentType(MediaType.APPLICATION_JSON)
            .body(SettlementRequestDto(userId = memberId(), amountCents = 1000, note = "paid"))
            .withAdmin()
            .exchange()

        val ledger = adminMemberLedger()
        assertThat(ledger.filter { it.type == LedgerEntryType.SETTLEMENT }.map { it.amountCents })
            .containsExactly(1000)
    }

    @Test
    fun `a negative kitty adjustment lowers the kitty balance`() {
        client()
            .post()
            .uri("/api/payments/adjustment")
            .contentType(MediaType.APPLICATION_JSON)
            .body(AdjustmentRequestDto(amountCents = 1000, note = "float"))
            .withAdmin()
            .exchange()
        client()
            .post()
            .uri("/api/payments/adjustment")
            .contentType(MediaType.APPLICATION_JSON)
            .body(AdjustmentRequestDto(amountCents = -250, note = "correction"))
            .withAdmin()
            .exchange()

        assertThat(kitty().balanceCents).isEqualTo(750)
        assertThat(kitty().entries.map { it.type }).contains(LedgerEntryType.KITTY_ADJUSTMENT)
    }

    @Test
    fun `an admin count override is valued as a lump on the member ledger`() {
        // the price is the seeded 50; an admin sets the count to 3 in one step -> a -150 lump
        client()
            .put()
            .uri("/api/users/{id}/consumption", memberId())
            .contentType(MediaType.APPLICATION_JSON)
            .body(ConsumptionOverrideDto(3, "manual"))
            .withAdmin()
            .exchange()

        val summary = memberSummary()
        assertThat(summary.count).isEqualTo(3)
        assertThat(summary.balanceCents).isEqualTo(-150)
        assertThat(summary.ledger.first { it.type == LedgerEntryType.CONSUMPTION }.amountCents).isEqualTo(-150)
    }

    @Test
    fun `a member buys then an admin attributes a split purchase building the full ledger`() {
        // a member's own purchase (100% private), an admin split purchase, a coffee, and a settlement
        client()
            .post()
            .uri("/api/expenses")
            .contentType(MediaType.APPLICATION_JSON)
            .body(mapOf("weightGrams" to 500, "amountCents" to 300))
            .withMember(member)
            .exchange()
        adminExpense(amountCents = 900, privateAmountCents = 400, kittyAmountCents = 500)
        client()
            .post()
            .uri("/api/consumption")
            .withMember(member)
            .exchange()
        client()
            .post()
            .uri("/api/payments/settlement")
            .contentType(MediaType.APPLICATION_JSON)
            .body(SettlementRequestDto(userId = memberId(), amountCents = 1000, note = null))
            .withAdmin()
            .exchange()

        // 300 + 400 - 50 + 1000 = 1650
        assertThat(memberSummary().balanceCents).isEqualTo(1650)
        val types = adminMemberLedger().map { it.type }.toSet()
        assertThat(types).contains(
            LedgerEntryType.PRIVATE_EXPENSE,
            LedgerEntryType.CONSUMPTION,
            LedgerEntryType.SETTLEMENT
        )
    }
}
