package de.seuhd.campuscoffee.domain.ports.data

import de.seuhd.campuscoffee.domain.exceptions.NotFoundException
import de.seuhd.campuscoffee.domain.model.CoffeeConsumption
import java.util.UUID

/**
 * Port interface for coffee-consumption data operations.
 *
 * This port is implemented by the data layer (adapter) and defines the contract for persistence
 * operations on the projected `coffee_consumptions` read table. Extends the generic [CrudDataService] to
 * inherit common CRUD operations and adds the one-per-user lookup the service relies on.
 */
interface CoffeeConsumptionDataService : CrudDataService<CoffeeConsumption, UUID> {
    /**
     * Retrieves the single consumption belonging to the user with [userId] (the table is unique on
     * `user_id`).
     *
     * @param userId the id of the owning user
     * @return that user's consumption
     * @throws NotFoundException if the user has no consumption
     */
    fun getByUserId(userId: UUID): CoffeeConsumption
}
