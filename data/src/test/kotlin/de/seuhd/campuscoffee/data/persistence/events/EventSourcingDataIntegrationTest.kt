package de.seuhd.campuscoffee.data.persistence.events
import de.seuhd.campuscoffee.data.persistence.entities.ChangeType
import de.seuhd.campuscoffee.domain.exceptions.DuplicationException
import de.seuhd.campuscoffee.domain.model.CoffeeConsumption
import de.seuhd.campuscoffee.domain.model.Role
import de.seuhd.campuscoffee.domain.model.User
import de.seuhd.campuscoffee.domain.model.persistedId
import de.seuhd.campuscoffee.domain.ports.data.ConsumptionHistoryDataService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * Integration tests for the event sourcing write path: every write appends a full-state event and projects
 * it into the read tables in one transaction, and the consumption history is read back from the log.
 */
class EventSourcingDataIntegrationTest : AbstractEventSourcingDataIntegrationTest() {
    @Autowired
    private lateinit var consumptionHistoryDataService: ConsumptionHistoryDataService

    @Autowired
    private lateinit var readModelProjector: ReadModelProjector

    private fun newUser(
        login: String,
        token: String
    ): User =
        User(
            loginName = login,
            emailAddress = "$login@se.de",
            firstName = "First",
            lastName = "Last",
            role = Role.USER,
            active = true,
            capabilityToken = token,
            passwordHash = "{noop}hash"
        )

    @Test
    fun `creating a user appends one INSERT event and projects it to the users read table`() {
        userDataService.upsert(newUser("alice", "token-alice"))

        assertThat(userRepository.findAll()).singleElement()
        val events = eventRepository.findAllByOrderBySeqAsc()
        assertThat(events).singleElement().satisfies({
            assertThat(it.entityType).isEqualTo("User")
            assertThat(it.changeType).isEqualTo(ChangeType.INSERT)
            assertThat(it.createdBy).isEqualTo("SYSTEM")
        })
    }

    @Test
    fun `a duplicate login name throws DuplicationException and rolls the event back`() {
        userDataService.upsert(newUser("dup", "token-1"))

        assertThatThrownBy { userDataService.upsert(newUser("dup", "token-2")) }
            .isInstanceOf(DuplicationException::class.java)

        // only the first user's event survived; the rolled-back insert left nothing behind
        assertThat(userRepository.findAll()).singleElement()
        assertThat(eventRepository.findAllByOrderBySeqAsc()).singleElement()
    }

    @Test
    fun `updating a consumption appends an UPDATE event and updates the read row`() {
        val user = userDataService.upsert(newUser("bob", "token-bob"))
        val consumption = coffeeConsumptionDataService.upsert(CoffeeConsumption(user = user, count = 0))

        coffeeConsumptionDataService.upsert(consumption.copy(count = 3))

        assertThat(coffeeConsumptionDataService.getByUserId(user.persistedId).count).isEqualTo(3)
        val consumptionEvents =
            eventRepository.findAllByOrderBySeqAsc().filter { it.entityType == "CoffeeConsumption" }
        assertThat(consumptionEvents.map { it.changeType })
            .containsExactly(ChangeType.INSERT, ChangeType.UPDATE)
    }

    @Test
    fun `the consumption history reflects the change log newest first with computed deltas`() {
        val user = userDataService.upsert(newUser("carol", "token-carol"))
        val consumption = coffeeConsumptionDataService.upsert(CoffeeConsumption(user = user, count = 0))
        coffeeConsumptionDataService.upsert(consumption.copy(count = 1))
        coffeeConsumptionDataService.upsert(consumption.copy(count = 2))

        val changes = consumptionHistoryDataService.changes(consumption.persistedId, 5, 0)

        assertThat(changes.map { it.count }).containsExactly(2, 1, 0)
        assertThat(changes.map { it.delta }).containsExactly(1, 1, 0)
        assertThat(changes).allSatisfy { assertThat(it.createdBy).isEqualTo("SYSTEM") }
    }

    @Test
    fun `the consumption history honors the limit and offset for paging`() {
        val user = userDataService.upsert(newUser("erin", "token-erin"))
        val consumption = coffeeConsumptionDataService.upsert(CoffeeConsumption(user = user, count = 0))
        coffeeConsumptionDataService.upsert(consumption.copy(count = 1))
        coffeeConsumptionDataService.upsert(consumption.copy(count = 2))

        // a zero limit returns nothing; a window of one at offset one returns the second-newest change
        assertThat(consumptionHistoryDataService.changes(consumption.persistedId, 0, 0)).isEmpty()
        val page = consumptionHistoryDataService.changes(consumption.persistedId, 1, 1)
        assertThat(page).singleElement().satisfies({ assertThat(it.count).isEqualTo(1) })
    }

    @Test
    fun `rebuilding the read model from the log restores the rows`() {
        val user = userDataService.upsert(newUser("dave", "token-dave"))
        coffeeConsumptionDataService.upsert(CoffeeConsumption(user = user, count = 4))

        // clear the read tables, then replay the whole log in append order
        coffeeConsumptionRepository.deleteAllInBatch()
        userRepository.deleteAllInBatch()
        eventRepository.findAllByOrderBySeqAsc().forEach { readModelProjector.apply(it) }

        assertThat(userRepository.findAll()).singleElement()
        assertThat(coffeeConsumptionDataService.getByUserId(user.persistedId).count).isEqualTo(4)
    }
}
