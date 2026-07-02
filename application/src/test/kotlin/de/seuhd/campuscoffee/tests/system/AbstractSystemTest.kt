package de.seuhd.campuscoffee.tests.system

import de.seuhd.campuscoffee.Application
import de.seuhd.campuscoffee.domain.model.User
import de.seuhd.campuscoffee.domain.ports.api.CoffeeBeanService
import de.seuhd.campuscoffee.domain.ports.api.CoffeeConsumptionService
import de.seuhd.campuscoffee.domain.ports.api.CoffeePriceService
import de.seuhd.campuscoffee.domain.ports.api.CoffeeRatingService
import de.seuhd.campuscoffee.domain.ports.api.ExpenseService
import de.seuhd.campuscoffee.domain.ports.api.PaymentService
import de.seuhd.campuscoffee.domain.ports.api.UserService
import de.seuhd.campuscoffee.domain.tests.TestFixtures
import de.seuhd.campuscoffee.tests.SystemTestUtils.configureClient
import de.seuhd.campuscoffee.tests.SystemTestUtils.configurePostgresContainers
import de.seuhd.campuscoffee.tests.SystemTestUtils.getPostgresContainer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer

/**
 * Abstract base class for system tests. Sets up the Spring Boot test context, manages the PostgreSQL
 * testcontainer, seeds the fixture users (with their deterministic capability tokens) and their zeroed
 * consumptions, and binds the shared RestTestClient. The id generator is the seeded (deterministic) one.
 */
@SpringBootTest(
    classes = [Application::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
abstract class AbstractSystemTest {
    @Autowired
    protected lateinit var userService: UserService

    @Autowired
    protected lateinit var coffeeConsumptionService: CoffeeConsumptionService

    @Autowired
    protected lateinit var coffeePriceService: CoffeePriceService

    @Autowired
    protected lateinit var expenseService: ExpenseService

    @Autowired
    protected lateinit var paymentService: PaymentService

    @Autowired
    protected lateinit var coffeeRatingService: CoffeeRatingService

    @Autowired
    protected lateinit var coffeeBeanService: CoffeeBeanService

    @LocalServerPort
    private var port: Int = 0

    /** The fixture users seeded before each test, available so a test can act as a specific one. */
    protected lateinit var seededUsers: List<User>

    @BeforeEach
    fun beforeEach() {
        clearAll()
        // seed the fixture users (with their known capability tokens and passwords) and their
        // consumptions at zero, so a user can authenticate by token and an admin by JWT
        seededUsers = TestFixtures.createUserFixtures(userService)
        TestFixtures.createConsumptionFixtures(coffeeConsumptionService, seededUsers)
        // seed the initial price after the users/consumptions, so a price is in effect before any coffee
        TestFixtures.createPriceFixture(coffeePriceService)
        configureClient(port)
    }

    @AfterEach
    fun afterEach() {
        clearAll()
    }

    /** The seeded user with the given login name. */
    protected fun seededUser(loginName: String): User = seededUsers.first { it.loginName == loginName }

    // Clears in foreign key order: ratings and expenses reference beans (and users), payments reference
    // users; so clear the money children and ratings, then consumptions, then users, then the beans the
    // ratings and expenses referenced, and the independent price.
    private fun clearAll() {
        expenseService.clear()
        paymentService.clear()
        coffeeRatingService.clear()
        coffeeConsumptionService.clear()
        userService.clear()
        coffeePriceService.clear()
        coffeeBeanService.clear()
    }

    protected companion object {
        // Shared across all system tests: a val in the companion object is a single instance, started once.
        protected val postgresContainer: PostgreSQLContainer<*> = getPostgresContainer().apply { start() }

        @JvmStatic
        @DynamicPropertySource
        fun configureProperties(registry: DynamicPropertyRegistry) {
            configurePostgresContainers(registry, postgresContainer)
            // the broad suite makes many failed logins on purpose from the same loopback client; the login
            // rate limiter has its own test (LoginRateLimitSystemTest), so turn it off here so an unrelated
            // test cannot hit the shared per-client failure budget
            registry.add("campus-coffee.auth.rate-limit.enabled") { "false" }
        }
    }
}
