package de.seuhd.campuscoffee.tests.system

import de.seuhd.campuscoffee.api.dtos.AdjustmentRequestDto
import de.seuhd.campuscoffee.api.dtos.AdminExpenseDto
import de.seuhd.campuscoffee.api.dtos.ConsumptionOverrideDto
import de.seuhd.campuscoffee.api.dtos.DepositRequestDto
import de.seuhd.campuscoffee.api.dtos.GlobalActivityEntryDto
import de.seuhd.campuscoffee.api.dtos.PriceUpdateDto
import de.seuhd.campuscoffee.api.dtos.UserDto
import de.seuhd.campuscoffee.domain.model.ActivityEntryType
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

/**
 * System tests for the admin global activity feed (`GET /api/users/activity`) and its CSV export
 * (`GET /api/users/activity.csv`): the whole-installation feed across all members, the kitty, and the price,
 * each row carrying the subject member and the actor and the member and kitty running balances. Covers the
 * admin-only authorization, the subject-vs-actor distinction, the two balance columns, price-change rows, the
 * CSV format (UTF-8 BOM, headers, a non-ASCII name round-trip), paging, and a hard-deleted subject.
 */
class AdminGlobalActivitySystemTests : AbstractSystemTest() {
    private val member = "maxmustermann"

    private fun memberId(): UUID = seededUser(member).persistedId

    private fun globalActivity(query: String = ""): List<GlobalActivityEntryDto> =
        client()
            .get()
            .uri("/api/users/activity$query")
            .accept(MediaType.APPLICATION_JSON)
            .withAdmin()
            .exchange()
            .returnResult<Array<GlobalActivityEntryDto>>()
            .responseBody!!
            .toList()

    private fun deposit(amountCents: Int) =
        client()
            .post()
            .uri("/api/kitty/deposit")
            .contentType(MediaType.APPLICATION_JSON)
            .body(DepositRequestDto(userId = memberId(), amountCents = amountCents, note = "paid"))
            .withAdmin()
            .exchange()

    private fun fundKitty(amountCents: Int) =
        client()
            .post()
            .uri("/api/kitty/adjustment")
            .contentType(MediaType.APPLICATION_JSON)
            .body(AdjustmentRequestDto(amountCents = amountCents, note = "float"))
            .withAdmin()
            .exchange()

    private fun overrideCount(
        userId: UUID,
        total: Int
    ) = client()
        .put()
        .uri("/api/users/{id}/consumption", userId)
        .contentType(MediaType.APPLICATION_JSON)
        .body(ConsumptionOverrideDto(total, "manual"))
        .withAdmin()
        .exchange()

    private fun createMember(
        login: String,
        first: String,
        last: String
    ): UUID =
        client()
            .post()
            .uri("/api/users")
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                UserDto(
                    loginName = login,
                    emailAddress = "$login@se.de",
                    firstName = first,
                    lastName = last,
                    role = Role.USER
                )
            ).withAdmin()
            .exchange()
            .returnResult<UserDto>()
            .responseBody!!
            .id!!

    @Test
    fun `the global activity feed returns 403 Forbidden for a member token`() {
        val status =
            client()
                .get()
                .uri("/api/users/activity")
                .withMember(member)
                .exchange()
                .statusCode()
        assertThat(status).isEqualTo(403)
    }

    @Test
    fun `the global activity CSV returns 403 Forbidden for a member token`() {
        val status =
            client()
                .get()
                .uri("/api/users/activity.csv")
                .withMember(member)
                .exchange()
                .statusCode()
        assertThat(status).isEqualTo(403)
    }

    @Test
    fun `the global feed records the subject member and the admin actor on a count override`() {
        // an admin (jane_doe via JWT) overrides maxmustermann's count: the row's subject is the member, the
        // actor is the admin, so the two user columns differ (a member self-scan would make them coincide)
        overrideCount(memberId(), 2)

        val entry =
            globalActivity().first { it.subjectLogin == member && it.type == ActivityEntryType.CONSUMPTION }
        assertThat(entry.subjectLogin).isEqualTo("maxmustermann")
        assertThat(entry.subjectName).isNotBlank()
        assertThat(entry.actorLogin).isEqualTo("jane_doe")
    }

    @Test
    fun `a deposit fills both the member and the kitty balance columns`() {
        deposit(1000)

        val entry = globalActivity().first { it.type == ActivityEntryType.DEPOSIT }
        assertThat(entry.memberEffectCents).isEqualTo(1000)
        assertThat(entry.memberBalanceCents).isNotNull()
        assertThat(entry.kittyEffectCents).isEqualTo(1000)
        assertThat(entry.kittyBalanceCents).isNotNull()
    }

    @Test
    fun `a fully kitty-funded expense fills only the kitty balance column`() {
        // fund the kitty so the fully kitty-funded purchase (800) stays non-negative
        fundKitty(1000)
        client()
            .post()
            .uri("/api/users/{id}/expenses", memberId())
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                AdminExpenseDto(
                    weightGrams = 750,
                    amountCents = 800,
                    privateAmountCents = 0,
                    kittyAmountCents = 800,
                    note = null
                )
            ).withAdmin()
            .exchange()

        // the expense surfaces as one global row (the buyer is the subject); it moves the kitty but not the
        // member, so the member columns are blank and the kitty columns carry the draw
        val entry = globalActivity().first { it.type == ActivityEntryType.PRIVATE_EXPENSE }
        assertThat(entry.subjectLogin).isEqualTo("maxmustermann")
        assertThat(entry.memberEffectCents).isNull()
        assertThat(entry.memberBalanceCents).isNull()
        assertThat(entry.kittyEffectCents).isEqualTo(-800)
        assertThat(entry.kittyBalanceCents).isNotNull()
    }

    @Test
    fun `a price change appears as a PRICE_CHANGE row carrying the new price and no balances`() {
        client()
            .put()
            .uri("/api/price")
            .contentType(MediaType.APPLICATION_JSON)
            .body(PriceUpdateDto(75))
            .withAdmin()
            .exchange()

        val entry = globalActivity().first { it.type == ActivityEntryType.PRICE_CHANGE && it.priceAmountCents == 75 }
        assertThat(entry.subjectUserId).isNull()
        assertThat(entry.memberEffectCents).isNull()
        assertThat(entry.kittyEffectCents).isNull()
    }

    @Test
    fun `the global feed pages newest first`() {
        deposit(100)
        deposit(200)
        deposit(300)

        val firstPage = globalActivity("?limit=1")
        assertThat(firstPage).hasSize(1)
        // the newest row leads the unpaged feed too
        assertThat(globalActivity("?limit=100").first().id).isEqualTo(firstPage.first().id)
        // the newest deposit (300) is the most recent money movement
        assertThat(firstPage.first().type).isEqualTo(ActivityEntryType.DEPOSIT)
        assertThat(firstPage.first().memberEffectCents).isEqualTo(300)
    }

    @Test
    fun `the global activity CSV is UTF-8 with a BOM, a header, and a non-ASCII subject round-tripping intact`() {
        // a member with an umlaut display name, given activity so it appears in the export
        val umlautId = createMember("juergen", "Jürgen", "Müller")
        overrideCount(umlautId, 1)

        val result =
            client()
                .get()
                .uri("/api/users/activity.csv")
                .withAdmin()
                .exchange()
                .returnResult<ByteArray>()

        assertThat(result.status.value()).isEqualTo(200)
        assertThat(result.responseHeaders.contentType.toString()).startsWith("text/csv")
        assertThat(result.responseHeaders.contentDisposition.filename).isEqualTo("activity.csv")
        val bytes = result.responseBody!!
        // a leading UTF-8 BOM so a spreadsheet (notably Excel) renders the umlaut name correctly
        assertThat(bytes.copyOfRange(0, 3)).isEqualTo(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
        val text = bytes.decodeToString()
        assertThat(text).contains("timestamp,type,subjectLogin,subjectName,actor")
        // the umlaut name survives the UTF-8 round-trip
        assertThat(text).contains("Müller")
    }

    @Test
    fun `the global feed renders a hard-deleted member's orphan events without failing`() {
        // a member who is bumped to one cup and corrected back to zero has no financial footprint, so they can
        // be hard-deleted; their consumption events remain in the append-only log as orphans
        val orphanId = createMember("ephemeral", "Eph", "Emeral")
        overrideCount(orphanId, 1)
        overrideCount(orphanId, 0)

        val deleteStatus =
            client()
                .delete()
                .uri("/api/users/{id}", orphanId)
                .withAdmin()
                .exchange()
                .statusCode()
        assertThat(deleteStatus).isEqualTo(204)

        // the feed still loads (no 500); the orphan's display name is gone with the profile, but the immutable
        // login is recovered from the log's User events so the rows stay identifiable and correctly classified
        val deletedRows =
            globalActivity().filter { it.subjectUserId == orphanId }
        assertThat(deletedRows).isNotEmpty()
        assertThat(deletedRows).allMatch { it.subjectName == "(deleted member)" && it.subjectLogin == "ephemeral" }
    }

    @Test
    fun `the CSV neutralizes a spreadsheet formula-injection display name`() {
        // a member whose display name begins with a formula trigger ('='): the CSV must prefix it with a quote
        // so a spreadsheet renders it as literal text rather than evaluating it (formula injection)
        val id = createMember("formulauser", "=cmd", "Test")
        overrideCount(id, 1)

        val text =
            client()
                .get()
                .uri("/api/users/activity.csv")
                .withAdmin()
                .exchange()
                .returnResult<ByteArray>()
                .responseBody!!
                .decodeToString()

        // the subject-name cell is guarded ("'=cmd Test"), never a bare leading '='
        assertThat(text).contains("'=cmd Test")
    }
}
