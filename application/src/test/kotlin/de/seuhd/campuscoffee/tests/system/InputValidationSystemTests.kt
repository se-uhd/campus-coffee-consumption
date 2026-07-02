package de.seuhd.campuscoffee.tests.system

import de.seuhd.campuscoffee.api.dtos.ConsumptionDeltaDto
import de.seuhd.campuscoffee.api.dtos.OwnExpenseDto
import de.seuhd.campuscoffee.domain.model.ExpenseType
import de.seuhd.campuscoffee.domain.model.persistedId
import de.seuhd.campuscoffee.tests.SystemTestUtils.client
import de.seuhd.campuscoffee.tests.SystemTestUtils.statusCode
import de.seuhd.campuscoffee.tests.SystemTestUtils.withAdmin
import de.seuhd.campuscoffee.tests.SystemTestUtils.withUser
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType

/**
 * System tests for the request-validation guards on the money and consumption endpoints: the fat-finger
 * `@Max` caps on a money amount and a bean weight, the minimum bean weight, and the single-step `delta`
 * rule that rejects a zero. Each malformed request is refused with a clean 400 before it reaches the domain.
 */
class InputValidationSystemTests : AbstractSystemTest() {
    private val user = "maxmustermann"

    private fun postOwnExpense(expense: OwnExpenseDto): Int =
        client()
            .post()
            .uri("/api/expenses")
            .contentType(MediaType.APPLICATION_JSON)
            .body(expense)
            .withUser(user)
            .exchange()
            .statusCode()

    @Test
    fun `a bean purchase above the money cap returns 400 Bad Request`() {
        // MAX_MONEY_CENTS is 1,000 EUR; one cent over the cap is refused
        val status =
            postOwnExpense(
                OwnExpenseDto(
                    expenseType = ExpenseType.BEANS,
                    beanName = "test beans",
                    weightGrams = 500,
                    amountCents = 100_001,
                    note = null
                )
            )

        assertThat(status).isEqualTo(400)
    }

    @Test
    fun `a bean purchase above the weight cap returns 400 Bad Request`() {
        // MAX_WEIGHT_GRAMS is 1,000 g; one gram over the cap is refused
        val status =
            postOwnExpense(
                OwnExpenseDto(
                    expenseType = ExpenseType.BEANS,
                    beanName = "test beans",
                    weightGrams = 1_001,
                    amountCents = 900,
                    note = null
                )
            )

        assertThat(status).isEqualTo(400)
    }

    @Test
    fun `a bean purchase below the minimum weight returns 400 Bad Request`() {
        // MIN_WEIGHT_GRAMS is 100 g; a lighter purchase is refused
        val status =
            postOwnExpense(
                OwnExpenseDto(
                    expenseType = ExpenseType.BEANS,
                    beanName = "test beans",
                    weightGrams = 50,
                    amountCents = 900,
                    note = null
                )
            )

        assertThat(status).isEqualTo(400)
    }

    @Test
    fun `an admin single-step count change of zero returns 400 Bad Request`() {
        val targetId = seededUser(user).persistedId
        val status =
            client()
                .post()
                .uri("/api/users/{id}/consumption", targetId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(ConsumptionDeltaDto(delta = 0))
                .withAdmin()
                .exchange()
                .statusCode()

        assertThat(status).isEqualTo(400)
    }
}
