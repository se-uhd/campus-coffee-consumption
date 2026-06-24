package de.seuhd.campuscoffee.domain.implementation

import de.seuhd.campuscoffee.domain.exceptions.DuplicationException
import de.seuhd.campuscoffee.domain.exceptions.ForbiddenException
import de.seuhd.campuscoffee.domain.exceptions.ValidationException
import de.seuhd.campuscoffee.domain.model.CoffeePrice
import de.seuhd.campuscoffee.domain.model.PriceChange
import de.seuhd.campuscoffee.domain.model.Role
import de.seuhd.campuscoffee.domain.model.User
import de.seuhd.campuscoffee.domain.ports.api.CoffeePriceService
import de.seuhd.campuscoffee.domain.ports.data.ActivityDataService
import de.seuhd.campuscoffee.domain.ports.data.CoffeePriceDataService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Domain implementation of [CoffeePriceService]. The price is a single global row: created the first time
 * one is set (or seeded at bootstrap) and updated in place thereafter, which the event-sourced data adapter
 * records as a full-state event, so the log keeps the full price history. Reading the current price is
 * open; changing it and reading its history are admin-only.
 */
@Service
class CoffeePriceServiceImpl(
    private val coffeePriceDataService: CoffeePriceDataService,
    private val activityDataService: ActivityDataService
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
        // An existing row is updated in place; the first write inserts the singleton. Two concurrent first
        // writes would both miss the existing row and both try to insert, so the loser hits the single-row
        // guard, which the data adapter maps to a domain DuplicationException (not a raw Spring exception):
        // handle it the way ensureInitialPrice does (re-read the winner's row and update it) rather than fail.
        if (current != null) {
            return coffeePriceDataService.upsert(current.copy(amountCents = amountCents))
        }
        return try {
            coffeePriceDataService.upsert(CoffeePrice(amountCents = amountCents))
        } catch (_: DuplicationException) {
            val winner = currentOrThrow()
            coffeePriceDataService.upsert(winner.copy(amountCents = amountCents))
        }
    }

    @Transactional
    override fun ensureInitialPrice(amountCents: Int): CoffeePrice {
        coffeePriceDataService.findCurrent()?.let { return it }
        return try {
            coffeePriceDataService.upsert(CoffeePrice(amountCents = amountCents))
        } catch (_: DuplicationException) {
            // two instances seeding the singleton at once: the loser hits the single-row guard (surfaced as a
            // domain DuplicationException by the data adapter). The winner's row is committed, so re-read it.
            currentOrThrow()
        }
    }

    override fun priceHistory(actingUser: User): List<PriceChange> {
        requireAdmin(actingUser)
        // the data service returns the history oldest-first; the API shows it newest-first
        return activityDataService.priceHistory().reversed()
    }

    override fun clear() = coffeePriceDataService.clear()

    /**
     * Re-reads the current price after a singleton-uniqueness conflict, when the winning concurrent write
     * has already committed the row. The row must exist by then, so its absence is unreachable.
     */
    private fun currentOrThrow(): CoffeePrice =
        requireNotNull(coffeePriceDataService.findCurrent()) {
            "The price singleton insert was rejected but no price exists; this should be unreachable."
        }

    /** Requires [actingUser] to be an admin, else 403. */
    private fun requireAdmin(actingUser: User) {
        if (actingUser.role != Role.ADMIN) {
            throw ForbiddenException("Only an admin may change or inspect the coffee price.")
        }
    }
}
