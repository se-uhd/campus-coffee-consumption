package de.seuhd.campuscoffee.tests.acceptance

import de.seuhd.campuscoffee.domain.ports.api.CoffeeConsumptionService
import de.seuhd.campuscoffee.domain.ports.api.CoffeePriceService
import de.seuhd.campuscoffee.domain.ports.api.ExpenseService
import de.seuhd.campuscoffee.domain.ports.api.PaymentService
import de.seuhd.campuscoffee.domain.ports.api.UserService
import de.seuhd.campuscoffee.domain.tests.TestFixtures
import de.seuhd.campuscoffee.tests.SystemTestUtils.configureClient
import de.seuhd.campuscoffee.tests.SystemTestUtils.configurePostgresContainers
import de.seuhd.campuscoffee.tests.SystemTestUtils.getPostgresContainer
import io.cucumber.java.After
import io.cucumber.java.Before
import io.cucumber.spring.CucumberContextConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer

/**
 * Single Spring and Cucumber configuration shared by all acceptance step definitions. Cucumber allows
 * only one [CucumberContextConfiguration], so the step classes hold step definitions only and rely on the
 * context, container, and cleanup hooks defined here. Each scenario starts from the seeded fixture users
 * (with their deterministic capability tokens) and their zeroed consumptions.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@CucumberContextConfiguration
class CucumberSpringConfiguration(
    private val userService: UserService,
    private val coffeeConsumptionService: CoffeeConsumptionService,
    private val coffeePriceService: CoffeePriceService,
    private val expenseService: ExpenseService,
    private val paymentService: PaymentService
) {
    @LocalServerPort
    private var port: Int = 0

    @Before
    fun beforeEach() {
        clearAll()
        val users = TestFixtures.createUserFixtures(userService)
        TestFixtures.createConsumptionFixtures(coffeeConsumptionService, users)
        TestFixtures.createPriceFixture(coffeePriceService)
        configureClient(port)
    }

    @After
    fun afterEach() {
        clearAll()
    }

    private fun clearAll() {
        expenseService.clear()
        paymentService.clear()
        coffeeConsumptionService.clear()
        userService.clear()
        coffeePriceService.clear()
    }

    companion object {
        // share one testcontainers instance across all acceptance tests
        private val postgresContainer: PostgreSQLContainer<*> = getPostgresContainer().apply { start() }

        @JvmStatic
        @DynamicPropertySource
        fun configureProperties(registry: DynamicPropertyRegistry) {
            configurePostgresContainers(registry, postgresContainer)
            // disable the login rate limiter for the acceptance suite (it is covered by its own system test),
            // so repeated scenario logins from the loopback client cannot trip the shared failure budget
            registry.add("campus-coffee.auth.rate-limit.enabled") { "false" }
        }
    }
}
