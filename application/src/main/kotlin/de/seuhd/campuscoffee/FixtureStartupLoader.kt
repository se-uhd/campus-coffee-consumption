package de.seuhd.campuscoffee

import de.seuhd.campuscoffee.configuration.FixturesProperties
import de.seuhd.campuscoffee.domain.ports.api.CoffeeBeanService
import de.seuhd.campuscoffee.domain.ports.api.CoffeeConsumptionService
import de.seuhd.campuscoffee.domain.ports.api.CoffeePriceService
import de.seuhd.campuscoffee.domain.ports.api.CoffeeRatingService
import de.seuhd.campuscoffee.domain.ports.api.ExpenseService
import de.seuhd.campuscoffee.domain.ports.api.PaymentService
import de.seuhd.campuscoffee.domain.ports.api.UserService
import de.seuhd.campuscoffee.domain.ports.system.IdGeneratorService
import de.seuhd.campuscoffee.domain.ports.system.StartupTaskService
import de.seuhd.campuscoffee.domain.tests.TestFixtures
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/**
 * Loads the fixture data on startup when `campus-coffee.fixtures.load-on-startup` is true (declared by
 * `FixturesProperties`) and the database has no users yet, seeding the demo users (with deterministic
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
    private val coffeeRatingService: CoffeeRatingService,
    private val coffeeBeanService: CoffeeBeanService,
    private val idGenerator: IdGeneratorService,
    private val fixturesProperties: FixturesProperties
) : StartupTaskService {
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
            log.info { "Reset and reseeded the fixture data on startup: $users users, $consumptions consumptions." }
            return
        }
        if (userService.getAll().isNotEmpty()) {
            log.info { "Skipping the fixture load: the database already has users." }
            return
        }
        val (users, consumptions) = TestFixtures.loadAll(userService, coffeeConsumptionService, coffeePriceService)
        log.info { "Loaded the fixture data on startup: $users users, $consumptions consumptions." }
    }

    /**
     * Clears all data in foreign key order: the children that reference users (expenses and payments are
     * RESTRICT, consumptions CASCADE) before the users, then the independent price.
     */
    private fun clearAll() {
        expenseService.clear()
        paymentService.clear()
        // ratings reference beans (and users), so clear them before the beans and users
        coffeeRatingService.clear()
        coffeeConsumptionService.clear()
        userService.clear()
        coffeePriceService.clear()
        // beans last: expenses and ratings reference a bean, so clear the referencers first
        coffeeBeanService.clear()
    }

    private companion object {
        // runs after the event sourcing rebuild (100) startup task, before the bootstrap admin (300)
        private const val ORDER = 200
        private val log = KotlinLogging.logger {}
    }
}
