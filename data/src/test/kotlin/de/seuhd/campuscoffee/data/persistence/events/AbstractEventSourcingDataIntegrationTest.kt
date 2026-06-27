package de.seuhd.campuscoffee.data.persistence.events
import de.seuhd.campuscoffee.data.integration.AbstractDataIntegrationTest
import de.seuhd.campuscoffee.data.persistence.repositories.EventRepository
import de.seuhd.campuscoffee.domain.ports.data.CoffeeConsumptionDataService
import de.seuhd.campuscoffee.domain.ports.data.UserDataService
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired

/**
 * Base class for the event sourcing data layer integration tests. Reuses [AbstractDataIntegrationTest]'s
 * PostgreSQL container and read model repositories. Event sourcing is the only persistence mode, so the
 * `@Primary` event-sourced decorators are the beans injected for the data-service ports below; every write
 * through them appends to the log and projects into the read tables. It also clears the event log before
 * each test.
 */
abstract class AbstractEventSourcingDataIntegrationTest : AbstractDataIntegrationTest() {
    @Autowired
    protected lateinit var userDataService: UserDataService

    @Autowired
    protected lateinit var coffeeConsumptionDataService: CoffeeConsumptionDataService

    @Autowired
    protected lateinit var eventRepository: EventRepository

    @BeforeEach
    fun clearEventLog() {
        eventRepository.deleteAllInBatch()
    }
}
