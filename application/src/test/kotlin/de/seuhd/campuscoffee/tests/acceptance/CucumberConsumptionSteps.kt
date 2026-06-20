package de.seuhd.campuscoffee.tests.acceptance

import de.seuhd.campuscoffee.api.dtos.ConsumptionDeltaDto
import de.seuhd.campuscoffee.api.dtos.ConsumptionDto
import de.seuhd.campuscoffee.tests.SystemTestUtils.client
import de.seuhd.campuscoffee.tests.SystemTestUtils.withMember
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.assertj.core.api.Assertions.assertThat
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.client.EntityExchangeResult
import tools.jackson.databind.ObjectMapper

/**
 * Step definitions for the member coffee-consumption acceptance scenarios. The member authenticates with
 * their seeded capability token; each step records the latest response so the Then steps can assert the
 * status and the returned total.
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
    fun theMemberAddsACoffee() = applyDelta(1)

    @When("the member removes a coffee")
    fun theMemberRemovesACoffee() = applyDelta(-1)

    @Then("the request succeeds and the coffee total is {int}")
    fun theRequestSucceedsAndTheTotalIs(total: Int) {
        assertThat(lastResult.status.value()).isEqualTo(200)
        val dto = objectMapper.readValue(lastResult.responseBody, ConsumptionDto::class.java)
        assertThat(dto.total).isEqualTo(total)
    }

    @Then("the request is rejected with status {int}")
    fun theRequestIsRejectedWithStatus(status: Int) {
        assertThat(lastResult.status.value()).isEqualTo(status)
    }

    private fun applyDelta(delta: Int) {
        lastResult =
            client()
                .post()
                .uri("/api/consumption")
                .contentType(MediaType.APPLICATION_JSON)
                .body(ConsumptionDeltaDto(delta))
                .withMember(member)
                .exchange()
                .returnResult(ByteArray::class.java)
    }
}
