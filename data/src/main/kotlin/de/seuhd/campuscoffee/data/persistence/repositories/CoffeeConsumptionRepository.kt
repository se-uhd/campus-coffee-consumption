package de.seuhd.campuscoffee.data.persistence.repositories

import de.seuhd.campuscoffee.data.persistence.entities.CoffeeConsumptionEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

/**
 * Repository for persisting coffee-consumption entities.
 */
interface CoffeeConsumptionRepository : JpaRepository<CoffeeConsumptionEntity, UUID> {
    /**
     * Returns the single consumption belonging to the user with the given id, or null if none exists
     * (Spring Data derives the query from the nested `user.id` path; the table is unique on `user_id`).
     *
     * @param userId the id of the owning user
     */
    fun findByUserId(userId: UUID): CoffeeConsumptionEntity?
}
