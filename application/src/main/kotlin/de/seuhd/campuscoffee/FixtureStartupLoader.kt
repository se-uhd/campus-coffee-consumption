package de.seuhd.campuscoffee

import de.seuhd.campuscoffee.configuration.FixturesProperties
import de.seuhd.campuscoffee.domain.ports.IdGenerator
import de.seuhd.campuscoffee.domain.ports.StartupTask
import de.seuhd.campuscoffee.domain.ports.api.CoffeeConsumptionService
import de.seuhd.campuscoffee.domain.ports.api.CoffeePriceService
import de.seuhd.campuscoffee.domain.ports.api.ExpenseService
import de.seuhd.campuscoffee.domain.ports.api.PaymentService
import de.seuhd.campuscoffee.domain.ports.api.UserService
import de.seuhd.campuscoffee.domain.tests.TestFixtures
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/**
 * Loads the fixture data on startup when `campus-coffee.fixtures.load-on-startup` is true (declared by
 * `FixturesProperties`) and the database has no users yet, seeding the demo members (with deterministic
 * capability tokens) and their zeroed coffee consumptions.
 *
 * [StartupDataInitializer] runs this before the web server accepts requests, after any event sourcing
 * rebuild, so the guard sees the rebuilt users and does not load the fixtures again, and the API is never
 * served before its data is loaded.
 */
@Component
@ConditionalOnProperty("campus-coffee.fixtures.load-on-startup", havingValue = "true")
class FixtureStartupLoader(
    private val userService: UserService,
    private val coffeeConsumptionService: CoffeeConsumptionService,
    private val coffeePriceService: CoffeePriceService,
    private val expenseService: ExpenseService,
    private val paymentService: PaymentService,
    private val idGenerator: IdGenerator,
    private val fixturesProperties: FixturesProperties
) : StartupTask {
    override val order = ORDER

    override fun run() = loadOnStartup()

    /**
     * Loads the fixture data. When `reset-on-startup` is set (dev only), every boot first restarts the id
     * sequence and clears all data, then reseeds, so the run always starts from the same deterministic state
     * and a persisted database can never collide with the freshly-restarted seeded id generators. Otherwise
     * it loads the fixtures only when the database has no users yet.
     */
    fun loadOnStartup() {
        if (fixturesProperties.resetOnStartup) {
            idGenerator.reset()
            clearAll()
            val (users, consumptions) = TestFixtures.loadAll(userService, coffeeConsumptionService, coffeePriceService)
            log.info("Reset and reseeded the fixture data on startup: {} users, {} consumptions.", users, consumptions)
            return
        }
        if (userService.getAll().isNotEmpty()) {
            log.info("Skipping the fixture load: the database already has users.")
            return
        }
        val (users, consumptions) = TestFixtures.loadAll(userService, coffeeConsumptionService, coffeePriceService)
        log.info("Loaded the fixture data on startup: {} users, {} consumptions.", users, consumptions)
    }

    /**
     * Clears all data in foreign key order: the children that reference users (expenses and payments are
     * RESTRICT, consumptions CASCADE) before the users, then the independent price.
     */
    private fun clearAll() {
        expenseService.clear()
        paymentService.clear()
        coffeeConsumptionService.clear()
        userService.clear()
        coffeePriceService.clear()
    }

    private companion object {
        // runs after the event sourcing rebuild (100) startup task, before the bootstrap admin (300)
        private const val ORDER = 200
        private val log = LoggerFactory.getLogger(FixtureStartupLoader::class.java)
    }
}
