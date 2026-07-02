package de.seuhd.campuscoffee.tests.system

import de.seuhd.campuscoffee.api.dtos.DepositRequestDto
import de.seuhd.campuscoffee.api.dtos.OwnExpenseDto
import de.seuhd.campuscoffee.api.dtos.PriceUpdateDto
import de.seuhd.campuscoffee.domain.model.ExpenseType
import de.seuhd.campuscoffee.domain.model.persistedId
import de.seuhd.campuscoffee.tests.SystemTestUtils.client
import de.seuhd.campuscoffee.tests.SystemTestUtils.withAdmin
import de.seuhd.campuscoffee.tests.SystemTestUtils.withUser
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import tools.jackson.databind.ObjectMapper
import javax.sql.DataSource

/**
 * System tests that assert the append-only event log records each money change as a full-state event of the
 * right entity_type, carrying the expected body fields and the acting user's login in created_by. The log is
 * read straight from the `events` table (entity_type, body jsonb, created_by, note) over a plain JDBC
 * connection from the application's DataSource, avoiding a dependency on the data layer's event types.
 */
class AccountingEventLogSystemTests : AbstractSystemTest() {
    @Autowired
    private lateinit var dataSource: DataSource

    private val objectMapper = ObjectMapper()

    private val user = "maxmustermann"

    private data class EventRow(
        val body: Map<String, Any?>,
        val createdBy: String?,
        val note: String?
    )

    private fun eventsOf(entityType: String): List<EventRow> {
        val rows = mutableListOf<EventRow>()
        dataSource.connection.use { connection ->
            connection
                .prepareStatement("SELECT body, created_by, note FROM events WHERE entity_type = ? ORDER BY seq ASC")
                .use { statement ->
                    statement.setString(1, entityType)
                    statement.executeQuery().use { rs ->
                        while (rs.next()) {
                            @Suppress("UNCHECKED_CAST")
                            val body =
                                objectMapper.readValue(
                                    rs.getString("body"),
                                    Map::class.java
                                ) as Map<String, Any?>
                            rows += EventRow(body, rs.getString("created_by"), rs.getString("note"))
                        }
                    }
                }
        }
        return rows
    }

    @Test
    fun `setting the price appends a CoffeePrice event with the new amount and the admin login`() {
        client()
            .put()
            .uri("/api/price")
            .contentType(MediaType.APPLICATION_JSON)
            .body(PriceUpdateDto(70))
            .withAdmin()
            .exchange()

        val priceEvents = eventsOf("CoffeePrice")
        // the seeded price (50) plus the just-set 70
        assertThat(priceEvents.map { (it.body["amountCents"] as Number).toInt() }).contains(50, 70)
        val latest = priceEvents.last()
        assertThat((latest.body["amountCents"] as Number).toInt()).isEqualTo(70)
        assertThat(latest.createdBy).isEqualTo("jane_doe")
    }

    @Test
    fun `a user purchase appends an Expense event with the buyer and amounts and the user login`() {
        client()
            .post()
            .uri("/api/expenses")
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                OwnExpenseDto(
                    expenseType = ExpenseType.BEANS,
                    beanName = "test beans",
                    weightGrams = 1000,
                    amountCents = 900,
                    note = "beans"
                )
            ).withUser(user)
            .exchange()

        val expenseEvents = eventsOf("Expense")
        assertThat(expenseEvents).hasSize(1)
        val event = expenseEvents.first()
        assertThat(event.body["buyerUserId"]).isEqualTo(seededUser(user).persistedId.toString())
        assertThat((event.body["amountCents"] as Number).toInt()).isEqualTo(900)
        assertThat((event.body["privateAmountCents"] as Number).toInt()).isEqualTo(900)
        assertThat((event.body["kittyAmountCents"] as Number).toInt()).isEqualTo(0)
        assertThat(event.createdBy).isEqualTo(user)
    }

    @Test
    fun `an admin count correction appends a CoffeeConsumption event carrying the note and the admin login`() {
        val reason = "manual correction: spilled a pot"
        client()
            .put()
            .uri("/api/users/{id}/consumption", seededUser(user).persistedId)
            .contentType(MediaType.APPLICATION_JSON)
            .body(mapOf("total" to 3, "note" to reason))
            .withAdmin()
            .exchange()

        // the latest consumption event (the correction) carries the note in the raw events.note column
        // (not the full-state body) and the acting admin's login in created_by
        val latest = eventsOf("CoffeeConsumption").last()
        assertThat((latest.body["count"] as Number).toInt()).isEqualTo(3)
        assertThat(latest.note).isEqualTo(reason)
        assertThat(latest.createdBy).isEqualTo("jane_doe")
        // the note is an event-row column, never part of the full-state JSON body
        assertThat(latest.body).doesNotContainKey("note")
    }

    @Test
    fun `a deposit appends a Payment event with the user and amount and the admin login`() {
        client()
            .post()
            .uri("/api/kitty/deposit")
            .contentType(MediaType.APPLICATION_JSON)
            .body(DepositRequestDto(userId = seededUser(user).persistedId, amountCents = 1000, note = "paid"))
            .withAdmin()
            .exchange()

        val paymentEvents = eventsOf("Payment")
        assertThat(paymentEvents).hasSize(1)
        val event = paymentEvents.first()
        assertThat(event.body["userId"]).isEqualTo(seededUser(user).persistedId.toString())
        assertThat((event.body["amountCents"] as Number).toInt()).isEqualTo(1000)
        // a deposit's note is carried in the full-state body (the event note column is the admin
        // override/reset reason, set only by the consumption service); the actor login is the admin
        assertThat(event.body["note"]).isEqualTo("paid")
        assertThat(event.createdBy).isEqualTo("jane_doe")
    }
}
