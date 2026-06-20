package de.seuhd.campuscoffee.data.persistence.eventsourcing

import de.seuhd.campuscoffee.domain.model.objects.CoffeeConsumption
import de.seuhd.campuscoffee.domain.model.objects.Role
import de.seuhd.campuscoffee.domain.model.objects.User
import de.seuhd.campuscoffee.domain.model.objects.persistedId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * Integration tests for the [EventsToDataRunner] rebuild: replaying the log restores the read tables, and
 * an empty log is a no-op (it never clears a populated read model with nothing to replay back).
 */
class EventsToDataRunnerTest : AbstractEventSourcingDataIntegrationTest() {
    @Autowired
    private lateinit var readModelProjector: ReadModelProjector

    private fun runner(): EventsToDataRunner =
        EventsToDataRunner(eventRepository, readModelProjector, userRepository, coffeeConsumptionRepository)

    @Test
    fun `rebuilding from the log restores the read rows after the tables are cleared`() {
        val user =
            userDataService.upsert(
                User(
                    loginName = "rebuild",
                    emailAddress = "rebuild@se.de",
                    firstName = "Re",
                    lastName = "Build",
                    role = Role.USER,
                    active = true,
                    capabilityToken = "token-rebuild",
                    passwordHash = "{noop}hash"
                )
            )
        coffeeConsumptionDataService.upsert(CoffeeConsumption(user = user, count = 5))

        coffeeConsumptionRepository.deleteAllInBatch()
        userRepository.deleteAllInBatch()

        runner().rebuildFromLog()

        assertThat(userRepository.findAll()).singleElement()
        assertThat(coffeeConsumptionDataService.getByUserId(user.persistedId).count).isEqualTo(5)
    }

    @Test
    fun `an empty log leaves the read tables untouched`() {
        val user =
            userDataService.upsert(
                User(
                    loginName = "kept",
                    emailAddress = "kept@se.de",
                    firstName = "Keep",
                    lastName = "Me",
                    role = Role.USER,
                    active = true,
                    capabilityToken = "token-kept",
                    passwordHash = "{noop}hash"
                )
            )
        // drop the log but keep the read row; the rebuild must refuse to wipe it
        eventRepository.deleteAllInBatch()

        runner().rebuildFromLog()

        assertThat(userRepository.findById(user.persistedId)).isPresent()
    }
}
