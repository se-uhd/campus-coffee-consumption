package de.seuhd.campuscoffee.tests.acceptance

import de.seuhd.campuscoffee.api.dtos.UserSummaryDto
import de.seuhd.campuscoffee.tests.SystemTestUtils.client
import de.seuhd.campuscoffee.tests.SystemTestUtils.withUser
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.assertj.core.api.Assertions.assertThat
import org.springframework.test.web.servlet.client.EntityExchangeResult
import org.springframework.test.web.servlet.client.returnResult
import tools.jackson.databind.ObjectMapper

/**
 * Step definitions for the user coffee-consumption acceptance scenarios. The user authenticates with
 * their seeded capability token; each step records the latest response so the Then steps can assert the
 * status and the returned count. Adding a coffee is `POST /consumption` (no body) and undoing the most
 * recent one is `POST /consumption/cancel`; both return the user summary.
 */
class CucumberConsumptionSteps(
    private val objectMapper: ObjectMapper
) {
    private lateinit var user: String
    private lateinit var lastResult: EntityExchangeResult<ByteArray>

    @Given("the user {string}")
    fun theUser(loginName: String) {
        user = loginName
    }

    @When("the user adds a coffee")
    fun theUserAddsACoffee() = post("/api/consumption")

    @When("the user undoes a coffee")
    fun theUserUndoesACoffee() = post("/api/consumption/cancel")

    @Then("the request succeeds and the coffee count is {int}")
    fun theRequestSucceedsAndTheCountIs(count: Int) {
        assertThat(lastResult.status.value()).isEqualTo(200)
        val dto = objectMapper.readValue(lastResult.responseBody, UserSummaryDto::class.java)
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
                .withUser(user)
                .exchange()
                .returnResult<ByteArray>()
    }
}
