package de.seuhd.campuscoffee.tests.acceptance

import de.seuhd.campuscoffee.api.dtos.DepositRequestDto
import de.seuhd.campuscoffee.api.dtos.GlobalActivityEntryDto
import de.seuhd.campuscoffee.api.dtos.PriceUpdateDto
import de.seuhd.campuscoffee.api.dtos.UserSummaryDto
import de.seuhd.campuscoffee.domain.model.ActivityEntryType
import de.seuhd.campuscoffee.domain.ports.api.UserService
import de.seuhd.campuscoffee.tests.SystemTestUtils.client
import de.seuhd.campuscoffee.tests.SystemTestUtils.withAdmin
import de.seuhd.campuscoffee.tests.SystemTestUtils.withMember
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.assertj.core.api.Assertions.assertThat
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.client.returnResult

/**
 * Step definitions for the money-feature acceptance scenarios: an admin sets the price, a member buys beans
 * and drinks coffee, an admin records a deposit, and the member's balance reflects each in euro cents
 * (negative ⇒ they owe the fund).
 */
class CucumberAccountingSteps(
    private val userService: UserService
) {
    private lateinit var member: String

    @Given("the coffee member {string}")
    fun theCoffeeMember(loginName: String) {
        member = loginName
    }

    @Given("an admin sets the price to {int} cents")
    fun anAdminSetsThePriceTo(amountCents: Int) {
        client()
            .put()
            .uri("/api/price")
            .contentType(MediaType.APPLICATION_JSON)
            .body(PriceUpdateDto(amountCents))
            .withAdmin()
            .exchange()
            .returnResult<ByteArray>()
    }

    @When("the member drinks a coffee")
    fun theMemberDrinksACoffee() {
        client()
            .post()
            .uri("/api/consumption")
            .withMember(member)
            .exchange()
            .returnResult<ByteArray>()
    }

    @When("the member buys beans for {int} cents")
    fun theMemberBuysBeansFor(amountCents: Int) {
        client()
            .post()
            .uri("/api/expenses")
            .contentType(MediaType.APPLICATION_JSON)
            .body(mapOf("weightGrams" to 1000, "amountCents" to amountCents))
            .withMember(member)
            .exchange()
            .returnResult<ByteArray>()
    }

    @When("an admin records a {int} cent deposit for the member")
    fun anAdminRecordsADeposit(amountCents: Int) {
        val id = userService.getByLoginName(member).id!!
        client()
            .post()
            .uri("/api/kitty/deposit")
            .contentType(MediaType.APPLICATION_JSON)
            .body(DepositRequestDto(userId = id, amountCents = amountCents, note = null))
            .withAdmin()
            .exchange()
            .returnResult<ByteArray>()
    }

    @Then("the member's balance is {int} cents")
    fun theMembersBalanceIs(balanceCents: Int) {
        val summary =
            client()
                .get()
                .uri("/api/summary")
                .accept(MediaType.APPLICATION_JSON)
                .withMember(member)
                .exchange()
                .returnResult<UserSummaryDto>()
                .responseBody!!
        assertThat(summary.balanceCents).isEqualTo(balanceCents.toLong())
    }

    @Then("the global activity feed shows a {word} entry for the member")
    fun theGlobalFeedShowsAnEntryForTheMember(type: String) {
        val entries =
            client()
                .get()
                .uri("/api/users/activity")
                .accept(MediaType.APPLICATION_JSON)
                .withAdmin()
                .exchange()
                .returnResult<Array<GlobalActivityEntryDto>>()
                .responseBody!!
        assertThat(entries)
            .anyMatch { it.type == ActivityEntryType.valueOf(type) && it.subjectLogin == member }
    }

    @Then("the activity CSV downloads with a UTF-8 BOM listing the member")
    fun theActivityCsvDownloads() {
        val result =
            client()
                .get()
                .uri("/api/users/activity.csv")
                .withAdmin()
                .exchange()
                .returnResult<ByteArray>()
        assertThat(result.status.value()).isEqualTo(200)
        val bytes = result.responseBody!!
        assertThat(bytes.copyOfRange(0, 3)).isEqualTo(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
        assertThat(bytes.decodeToString()).contains(member)
    }
}
