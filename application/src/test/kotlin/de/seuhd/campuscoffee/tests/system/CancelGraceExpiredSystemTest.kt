package de.seuhd.campuscoffee.tests.system

import de.seuhd.campuscoffee.Application
import de.seuhd.campuscoffee.domain.ports.api.CoffeeConsumptionService
import de.seuhd.campuscoffee.domain.ports.api.CoffeePriceService
import de.seuhd.campuscoffee.domain.ports.api.ExpenseService
import de.seuhd.campuscoffee.domain.ports.api.PaymentService
import de.seuhd.campuscoffee.domain.ports.api.UserService
import de.seuhd.campuscoffee.domain.tests.TestFixtures
import de.seuhd.campuscoffee.tests.SystemTestUtils.client
import de.seuhd.campuscoffee.tests.SystemTestUtils.configureClient
import de.seuhd.campuscoffee.tests.SystemTestUtils.configurePostgresContainers
import de.seuhd.campuscoffee.tests.SystemTestUtils.getPostgresContainer
import de.seuhd.campuscoffee.tests.SystemTestUtils.statusCode
import de.seuhd.campuscoffee.tests.SystemTestUtils.withMember
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer

/**
 * System test for the cancel grace period having expired: a dedicated context with a zero-length grace
 * period (`campus-coffee.consumption.cancel-grace-period=0s`), so a coffee can never be undone: the cancel
 * is immediately too late and returns 409.
 */
@SpringBootTest(classes = [Application::class], webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CancelGraceExpiredSystemTest {
    @Autowired
    private lateinit var userService: UserService

    @Autowired
    private lateinit var coffeeConsumptionService: CoffeeConsumptionService

    @Autowired
    private lateinit var coffeePriceService: CoffeePriceService

    @Autowired
    private lateinit var expenseService: ExpenseService

    @Autowired
    private lateinit var paymentService: PaymentService

    @LocalServerPort
    private var port: Int = 0

    private val member = "maxmustermann"

    @BeforeEach
    fun setUp() {
        clearAll()
        val users = TestFixtures.createUserFixtures(userService)
        TestFixtures.createConsumptionFixtures(coffeeConsumptionService, users)
        TestFixtures.createPriceFixture(coffeePriceService)
        configureClient(port)
    }

    @AfterEach
    fun tearDown() = clearAll()

    private fun clearAll() {
        expenseService.clear()
        paymentService.clear()
        coffeeConsumptionService.clear()
        userService.clear()
        coffeePriceService.clear()
    }

    @Test
    fun `cancelling after the grace period has passed returns 409 Conflict`() {
        // add a coffee, then try to undo it; with a zero grace period the undo is already too late
        client()
            .post()
            .uri("/api/consumption")
            .withMember(member)
            .exchange()

        val status =
            client()
                .post()
                .uri("/api/consumption/cancel")
                .withMember(member)
                .exchange()
                .statusCode()

        assertThat(status).isEqualTo(409)
    }

    companion object {
        private val postgresContainer: PostgreSQLContainer<*> = getPostgresContainer().apply { start() }

        @JvmStatic
        @DynamicPropertySource
        fun configureProperties(registry: DynamicPropertyRegistry) {
            configurePostgresContainers(registry, postgresContainer)
            registry.add("campus-coffee.consumption.cancel-grace-period") { "0s" }
        }
    }
}
