package de.seuhd.campuscoffee.data.integration

import de.seuhd.campuscoffee.data.DataTestApplication
import de.seuhd.campuscoffee.data.persistence.entities.Entity
import de.seuhd.campuscoffee.data.persistence.repositories.CoffeeConsumptionRepository
import de.seuhd.campuscoffee.data.persistence.repositories.UserRepository
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.util.UUID

/**
 * Base class for data layer integration tests. Boots the data layer against a real PostgreSQL
 * container with the Flyway-managed schema and clears the tables before each test.
 */
@SpringBootTest(classes = [DataTestApplication::class], webEnvironment = SpringBootTest.WebEnvironment.NONE)
abstract class AbstractDataIntegrationTest {
    @Autowired
    protected lateinit var userRepository: UserRepository

    @Autowired
    protected lateinit var coffeeConsumptionRepository: CoffeeConsumptionRepository

    @BeforeEach
    fun clearDatabase() {
        // consumptions reference users, so clear them first
        coffeeConsumptionRepository.deleteAllInBatch()
        userRepository.deleteAllInBatch()
    }

    /**
     * Assigns an id to an entity that a test persists directly through a repository. The data services
     * assign the id themselves (via the `IdGeneratorService`); a test that bypasses them and saves an entity
     * built straight from the mapper must set the id here, because the database does not generate it.
     */
    protected fun <T : Entity> T.withGeneratedId(): T = apply { id = UUID.randomUUID() }

    companion object {
        private val postgresContainer = PostgreSQLContainer<Nothing>(DockerImageName.parse("postgres:18-alpine"))

        init {
            postgresContainer.start()
        }

        @JvmStatic
        @DynamicPropertySource
        fun configureProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgresContainer::getJdbcUrl)
            registry.add("spring.datasource.username", postgresContainer::getUsername)
            registry.add("spring.datasource.password", postgresContainer::getPassword)
        }
    }
}
