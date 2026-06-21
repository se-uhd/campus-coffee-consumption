package de.seuhd.campuscoffee.domain.ports.data

import de.seuhd.campuscoffee.domain.model.CoffeePrice
import java.util.UUID

/**
 * Port interface for coffee-price data operations, implemented by the data layer. Extends the generic
 * [CrudDataService] and adds the single-row lookup the price is modeled as. The price is a global singleton
 * in the read model (created once, then updated in place), so there is at most one row.
 */
interface CoffeePriceDataService : CrudDataService<CoffeePrice, UUID> {
    /**
     * Returns the single current price row, or null if none has been seeded yet. Used by the startup
     * loader to decide whether to create the initial price and by the price service to read the current
     * value.
     *
     * @return the current price, or null if no price exists yet
     */
    fun findCurrent(): CoffeePrice?
}
