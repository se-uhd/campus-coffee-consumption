package de.seuhd.campuscoffee

import de.seuhd.campuscoffee.configuration.CoffeePriceProperties
import de.seuhd.campuscoffee.domain.ports.StartupTask
import de.seuhd.campuscoffee.domain.ports.api.CoffeePriceService
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component

/**
 * Seeds the initial coffee price on startup if none exists yet, so a price is always in effect before any
 * coffee is consumed. Idempotent: a restart, a log rebuild, or a dev profile (where the fixtures already
 * seed a price) leaves the existing price untouched.
 *
 * [StartupDataInitializer] runs it before the web server accepts requests, after the fixture loader, so in
 * dev it is a no-op and in prod (where fixtures are off) it seeds the configured default.
 */
@Component
class CoffeePriceStartupLoader(
    private val coffeePriceService: CoffeePriceService,
    private val coffeePriceProperties: CoffeePriceProperties
) : StartupTask {
    override val order = ORDER

    override fun run() = seedInitialPrice()

    /** Creates the initial price at the configured default unless one already exists. */
    fun seedInitialPrice() {
        val price = coffeePriceService.ensureInitialPrice(coffeePriceProperties.initialCents)
        log.info { "The coffee price is ${price.amountCents} cents per cup." }
    }

    private companion object {
        // runs after the fixture loader (200); in dev the fixtures already seeded a price, so this no-ops
        private const val ORDER = 250
        private val log = KotlinLogging.logger {}
    }
}
