package de.seuhd.campuscoffee.domain.implementation

import de.seuhd.campuscoffee.domain.exceptions.ForbiddenException
import de.seuhd.campuscoffee.domain.exceptions.ValidationException
import de.seuhd.campuscoffee.domain.model.CoffeePrice
import de.seuhd.campuscoffee.domain.model.PriceChange
import de.seuhd.campuscoffee.domain.model.Role
import de.seuhd.campuscoffee.domain.model.User
import de.seuhd.campuscoffee.domain.ports.api.CoffeePriceService
import de.seuhd.campuscoffee.domain.ports.data.CoffeePriceDataService
import de.seuhd.campuscoffee.domain.ports.data.LedgerDataService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Domain implementation of [CoffeePriceService]. The price is a single global row: created the first time
 * one is set (or seeded at bootstrap) and updated in place thereafter, which the event-sourced data adapter
 * records as a full-state event — so the log keeps the full price history. Reading the current price is
 * open; changing it and reading its history are admin-only.
 */
@Service
class CoffeePriceServiceImpl(
    private val coffeePriceDataService: CoffeePriceDataService,
    private val ledgerDataService: LedgerDataService
) : CoffeePriceService {
    override fun getCurrent(): CoffeePrice =
        coffeePriceDataService.findCurrent()
            ?: error("No coffee price has been seeded; a price must be created at bootstrap.")

    @Transactional
    override fun setPrice(
        amountCents: Int,
        actingUser: User
    ): CoffeePrice {
        requireAdmin(actingUser)
        if (amountCents < 0) {
            throw ValidationException("The coffee price cannot be negative.")
        }
        val current = coffeePriceDataService.findCurrent()
        val toSave = current?.copy(amountCents = amountCents) ?: CoffeePrice(amountCents = amountCents)
        return coffeePriceDataService.upsert(toSave)
    }

    @Transactional
    override fun ensureInitialPrice(amountCents: Int): CoffeePrice =
        coffeePriceDataService.findCurrent() ?: coffeePriceDataService.upsert(CoffeePrice(amountCents = amountCents))

    override fun priceHistory(actingUser: User): List<PriceChange> {
        requireAdmin(actingUser)
        // the data service returns the history oldest-first; the API shows it newest-first
        return ledgerDataService.priceHistory().reversed()
    }

    override fun clear() = coffeePriceDataService.clear()

    /** Requires [actingUser] to be an admin, else 403. */
    private fun requireAdmin(actingUser: User) {
        if (actingUser.role != Role.ADMIN) {
            throw ForbiddenException("Only an admin may change or inspect the coffee price.")
        }
    }
}
