package de.seuhd.campuscoffee.tests.acceptance

import de.seuhd.campuscoffee.api.dtos.MemberSummaryDto
import de.seuhd.campuscoffee.api.dtos.PriceUpdateDto
import de.seuhd.campuscoffee.api.dtos.SettlementRequestDto
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
 * and drinks coffee, an admin records a settlement, and the member's balance reflects each in euro cents
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

    @When("an admin records a {int} cent settlement for the member")
    fun anAdminRecordsASettlement(amountCents: Int) {
        val id = userService.getByLoginName(member).id!!
        client()
            .post()
            .uri("/api/kitty/deposit")
            .contentType(MediaType.APPLICATION_JSON)
            .body(SettlementRequestDto(userId = id, amountCents = amountCents, note = null))
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
                .returnResult<MemberSummaryDto>()
                .responseBody!!
        assertThat(summary.balanceCents).isEqualTo(balanceCents.toLong())
    }
}
