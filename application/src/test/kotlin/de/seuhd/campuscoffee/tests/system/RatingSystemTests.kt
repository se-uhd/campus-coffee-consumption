package de.seuhd.campuscoffee.tests.system

import de.seuhd.campuscoffee.api.dtos.ActivityEntryDto
import de.seuhd.campuscoffee.api.dtos.CoffeeBeanDto
import de.seuhd.campuscoffee.api.dtos.CoffeeBeanRatingsDto
import de.seuhd.campuscoffee.api.dtos.OwnExpenseDto
import de.seuhd.campuscoffee.api.dtos.RatingRequestDto
import de.seuhd.campuscoffee.api.dtos.UserSummaryDto
import de.seuhd.campuscoffee.domain.model.ActivityEntryType
import de.seuhd.campuscoffee.domain.model.ExpenseType
import de.seuhd.campuscoffee.domain.model.persistedId
import de.seuhd.campuscoffee.tests.SystemTestUtils.client
import de.seuhd.campuscoffee.tests.SystemTestUtils.statusCode
import de.seuhd.campuscoffee.tests.SystemTestUtils.withAdmin
import de.seuhd.campuscoffee.tests.SystemTestUtils.withUser
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.client.returnResult
import java.util.UUID

/**
 * System tests for the rating flow and the bean catalog over HTTP: recording a bean purchase populates the
 * catalog, a user rates the beans of their current cup, the ratings reflect the vote and the purchase, a
 * rating without a cancellable cup is refused, and an admin can rename a bean.
 */
class RatingSystemTests : AbstractSystemTest() {
    private val user = "maxmustermann"

    private fun recordBeanPurchase(beanName: String) {
        client()
            .post()
            .uri("/api/expenses")
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                OwnExpenseDto(
                    expenseType = ExpenseType.BEANS,
                    beanName = beanName,
                    weightGrams = 1000,
                    amountCents = 900,
                    note = null
                )
            ).withUser(user)
            .exchange()
    }

    private fun beans(): List<CoffeeBeanDto> =
        client()
            .get()
            .uri("/api/beans")
            .accept(MediaType.APPLICATION_JSON)
            .withUser(user)
            .exchange()
            .returnResult<Array<CoffeeBeanDto>>()
            .responseBody!!
            .toList()

    private fun addCoffee() {
        client()
            .post()
            .uri("/api/consumption")
            .withUser(user)
            .exchange()
    }

    private fun rate(
        beanId: UUID,
        value: Int
    ) = client()
        .put()
        .uri("/api/consumption/rating")
        .contentType(MediaType.APPLICATION_JSON)
        .body(RatingRequestDto(beanId = beanId, value = value))
        .withUser(user)

    private fun ratings(): List<CoffeeBeanRatingsDto> =
        client()
            .get()
            .uri("/api/beans/ratings")
            .accept(MediaType.APPLICATION_JSON)
            .withAdmin()
            .exchange()
            .returnResult<Array<CoffeeBeanRatingsDto>>()
            .responseBody!!
            .toList()

    @Test
    fun `a user rates the beans of their current cup and the ratings reflect it`() {
        recordBeanPurchase("Ethiopia Yirgacheffe")
        val beanId = beans().first { it.name == "Ethiopia Yirgacheffe" }.id
        addCoffee()

        val summary =
            rate(beanId, 4)
                .exchange()
                .returnResult<UserSummaryDto>()
                .responseBody!!
        assertThat(summary.ratingPrompt.canRate).isTrue()
        assertThat(summary.ratingPrompt.value).isEqualTo(4)

        val row = ratings().first { it.beanId == beanId }
        assertThat(row.voteCount).isEqualTo(1)
        assertThat(row.averageValue).isEqualTo(4.0)
        assertThat(row.latestPurchaseAt).isNotNull()
    }

    @Test
    fun `re-rating the same cup updates the one vote`() {
        recordBeanPurchase("Colombia Supremo")
        val beanId = beans().first { it.name == "Colombia Supremo" }.id
        addCoffee()

        rate(beanId, 2).exchange()
        rate(beanId, 5).exchange()

        val row = ratings().first { it.beanId == beanId }
        assertThat(row.voteCount).isEqualTo(1)
        assertThat(row.averageValue).isEqualTo(5.0)
    }

    @Test
    fun `a rating appears in the user's activity feed with its bean and value`() {
        recordBeanPurchase("Sumatra Mandheling")
        val beanId = beans().first { it.name == "Sumatra Mandheling" }.id
        addCoffee()
        rate(beanId, 4).exchange()

        val activity =
            client()
                .get()
                .uri("/api/activity")
                .accept(MediaType.APPLICATION_JSON)
                .withUser(user)
                .exchange()
                .returnResult<Array<ActivityEntryDto>>()
                .responseBody!!
                .toList()
        val row = activity.first { it.type == ActivityEntryType.RATING }
        assertThat(row.beanName).isEqualTo("Sumatra Mandheling")
        assertThat(row.ratingValue).isEqualTo(4)
        // a rating moves no money
        assertThat(row.amountCents).isEqualTo(0)
    }

    @Test
    fun `the global activity CSV export includes rating rows with the bean and value`() {
        recordBeanPurchase("Yemen Mocha")
        val beanId = beans().first { it.name == "Yemen Mocha" }.id
        addCoffee()
        rate(beanId, 4).exchange()

        val csv =
            client()
                .get()
                .uri("/api/users/activity.csv")
                .withAdmin()
                .exchange()
                .returnResult<ByteArray>()
                .responseBody!!
                .decodeToString()
        // the two rating columns are in the header, and the rating row carries its bean and value
        assertThat(csv).contains("beanName,ratingValue")
        assertThat(csv).contains("RATING").contains("Yemen Mocha")
    }

    @Test
    fun `re-rating a different bean in the same window moves the one vote and leaves both bean names intact`() {
        recordBeanPurchase("Java Jampit")
        recordBeanPurchase("Panama Geisha")
        val first = beans().first { it.name == "Java Jampit" }.id
        val second = beans().first { it.name == "Panama Geisha" }.id
        addCoffee()

        rate(first, 4).exchange()
        // switching the bean within the same window must repoint the single vote, not rewrite the first bean
        assertThat(rate(second, 5).exchange().statusCode()).isEqualTo(200)

        val rows = ratings()
        assertThat(rows.sumOf { it.voteCount }).isEqualTo(1)
        val secondRow = rows.first { it.beanId == second }
        assertThat(secondRow.voteCount).isEqualTo(1)
        assertThat(secondRow.averageValue).isEqualTo(5.0)
        // the projection must not have renamed one bean's row onto the other
        assertThat(beans().map { it.name }).contains("Java Jampit", "Panama Geisha")
    }

    @Test
    fun `rating without a cancellable cup returns 409 Conflict`() {
        recordBeanPurchase("Brazil Santos")
        val beanId = beans().first { it.name == "Brazil Santos" }.id
        // no coffee added, so there is no cancellable cup to rate

        assertThat(rate(beanId, 3).exchange().statusCode()).isEqualTo(409)
    }

    @Test
    fun `a rating value out of range returns 400 Bad Request`() {
        recordBeanPurchase("Guatemala Antigua")
        val beanId = beans().first { it.name == "Guatemala Antigua" }.id
        addCoffee()

        assertThat(rate(beanId, 6).exchange().statusCode()).isEqualTo(400)
    }

    @Test
    fun `an admin rates a user's current cup on their behalf`() {
        recordBeanPurchase("Kenya AA")
        val beanId = beans().first { it.name == "Kenya AA" }.id
        // the user self-scans, so their cup is an owner increment (cancellable); the admin then rates it
        addCoffee()

        val userId = seededUser(user).persistedId
        val status =
            client()
                .put()
                .uri("/api/users/{id}/consumption/rating", userId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(RatingRequestDto(beanId = beanId, value = 5))
                .withAdmin()
                .exchange()
                .statusCode()
        assertThat(status).isEqualTo(200)

        val row = ratings().first { it.beanId == beanId }
        assertThat(row.voteCount).isEqualTo(1)
        assertThat(row.averageValue).isEqualTo(5.0)
    }

    @Test
    fun `an admin renames a bean and the catalog reflects the new name`() {
        recordBeanPurchase("Typo Bean")
        val beanId = beans().first { it.name == "Typo Bean" }.id

        val status =
            client()
                .put()
                .uri("/api/beans/{id}", beanId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(mapOf("name" to "Sumatra Mandheling"))
                .withAdmin()
                .exchange()
                .statusCode()
        assertThat(status).isEqualTo(200)

        assertThat(beans().map { it.name }).contains("Sumatra Mandheling").doesNotContain("Typo Bean")
    }
}
