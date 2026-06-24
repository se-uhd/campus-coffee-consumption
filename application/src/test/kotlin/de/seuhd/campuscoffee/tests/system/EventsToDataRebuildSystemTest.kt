package de.seuhd.campuscoffee.tests.system

import de.seuhd.campuscoffee.Application
import de.seuhd.campuscoffee.api.dtos.KittyDto
import de.seuhd.campuscoffee.api.dtos.UserSummaryDto
import de.seuhd.campuscoffee.data.persistence.eventsourcing.EventsToDataRunner
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
import de.seuhd.campuscoffee.tests.SystemTestUtils.withAdmin
import de.seuhd.campuscoffee.tests.SystemTestUtils.withMember
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.client.returnResult
import org.testcontainers.containers.PostgreSQLContainer

/**
 * System test for the event sourcing rebuild: with `events-to-data-on-startup` on so the [EventsToDataRunner]
 * bean exists, seed users, consumptions, a price, an expense, and a deposit; then rebuild the read tables
 * from the log and assert the balance, kitty, and activity are unchanged: the log is a faithful source of
 * truth.
 */
@SpringBootTest(classes = [Application::class], webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EventsToDataRebuildSystemTest {
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

    @Autowired
    private lateinit var eventsToDataRunner: EventsToDataRunner

    @LocalServerPort
    private var port: Int = 0

    private val member = "maxmustermann"

    @BeforeEach
    fun setUp() {
        clearAll()
        TestFixtures.createUserFixtures(userService).let {
            TestFixtures.createConsumptionFixtures(coffeeConsumptionService, it)
        }
        TestFixtures.createPriceFixture(coffeePriceService)
        configureClient(port)
    }

    private fun clearAll() {
        expenseService.clear()
        paymentService.clear()
        coffeeConsumptionService.clear()
        userService.clear()
        coffeePriceService.clear()
    }

    private fun memberId() = userService.getByLoginName(member).id!!

    private fun summary(): UserSummaryDto =
        client()
            .get()
            .uri("/api/summary")
            .accept(MediaType.APPLICATION_JSON)
            .withMember(member)
            .exchange()
            .returnResult<UserSummaryDto>()
            .responseBody!!

    private fun kittyBalance(): Long =
        client()
            .get()
            .uri("/api/kitty/history")
            .accept(MediaType.APPLICATION_JSON)
            .withAdmin()
            .exchange()
            .returnResult<KittyDto>()
            .responseBody!!
            .balanceCents

    @Test
    fun `rebuilding the read model from the log leaves the balance, kitty, and activity unchanged`() {
        // a coffee at 50, a member purchase (+900 private), and a deposit (+1000) -> balance 1850
        client()
            .post()
            .uri("/api/consumption")
            .withMember(member)
            .exchange()
        client()
            .post()
            .uri("/api/expenses")
            .contentType(MediaType.APPLICATION_JSON)
            .body(mapOf("weightGrams" to 1000, "amountCents" to 900))
            .withMember(member)
            .exchange()
        client()
            .post()
            .uri("/api/kitty/deposit")
            .contentType(MediaType.APPLICATION_JSON)
            .body(mapOf("userId" to memberId().toString(), "amountCents" to 1000))
            .withAdmin()
            .exchange()

        val before = summary()
        val kittyBefore = kittyBalance()
        assertThat(before.balanceCents).isEqualTo(1850)

        eventsToDataRunner.rebuildFromLog()

        val after = summary()
        assertThat(after.count).isEqualTo(before.count)
        assertThat(after.balanceCents).isEqualTo(before.balanceCents)
        assertThat(after.activity.map { it.type }).isEqualTo(before.activity.map { it.type })
        assertThat(kittyBalance()).isEqualTo(kittyBefore)
    }

    companion object {
        private val postgresContainer: PostgreSQLContainer<*> = getPostgresContainer().apply { start() }

        @JvmStatic
        @DynamicPropertySource
        fun configureProperties(registry: DynamicPropertyRegistry) {
            configurePostgresContainers(registry, postgresContainer)
            // turn the rebuild runner on so its bean is registered (we invoke it directly in the test)
            registry.add("campus-coffee.persistence.events-to-data-on-startup") { "true" }
        }
    }
}
