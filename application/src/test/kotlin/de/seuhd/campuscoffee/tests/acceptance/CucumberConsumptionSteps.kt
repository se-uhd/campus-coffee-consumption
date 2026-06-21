package de.seuhd.campuscoffee.tests.acceptance

import de.seuhd.campuscoffee.api.dtos.MemberSummaryDto
import de.seuhd.campuscoffee.tests.SystemTestUtils.client
import de.seuhd.campuscoffee.tests.SystemTestUtils.withMember
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.assertj.core.api.Assertions.assertThat
import org.springframework.test.web.servlet.client.EntityExchangeResult
import org.springframework.test.web.servlet.client.returnResult
import tools.jackson.databind.ObjectMapper

/**
 * Step definitions for the member coffee-consumption acceptance scenarios. The member authenticates with
 * their seeded capability token; each step records the latest response so the Then steps can assert the
 * status and the returned count. Adding a coffee is `POST /consumption` (no body) and undoing the most
 * recent one is `POST /consumption/cancel`; both return the member summary.
 */
class CucumberConsumptionSteps(
    private val objectMapper: ObjectMapper
) {
    private lateinit var member: String
    private lateinit var lastResult: EntityExchangeResult<ByteArray>

    @Given("the member {string}")
    fun theMember(loginName: String) {
        member = loginName
    }

    @When("the member adds a coffee")
    fun theMemberAddsACoffee() = post("/api/consumption")

    @When("the member undoes a coffee")
    fun theMemberUndoesACoffee() = post("/api/consumption/cancel")

    @Then("the request succeeds and the coffee count is {int}")
    fun theRequestSucceedsAndTheCountIs(count: Int) {
        assertThat(lastResult.status.value()).isEqualTo(200)
        val dto = objectMapper.readValue(lastResult.responseBody, MemberSummaryDto::class.java)
        assertThat(dto.count).isEqualTo(count)
    }

    @Then("the request is rejected with status {int}")
    fun theRequestIsRejectedWithStatus(status: Int) {
        assertThat(lastResult.status.value()).isEqualTo(status)
    }

    private fun post(uri: String) {
        lastResult =
            client()
                .post()
                .uri(uri)
                .withMember(member)
                .exchange()
                .returnResult<ByteArray>()
    }
}
