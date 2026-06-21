package de.seuhd.campuscoffee.data.persistence.repositories

import de.seuhd.campuscoffee.data.persistence.entities.ExpenseEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

/**
 * Repository for persisting expense entities.
 */
interface ExpenseRepository : JpaRepository<ExpenseEntity, UUID> {
    /**
     * Returns all expenses whose buyer is the user with the given id (Spring Data derives the query from
     * the nested `buyer.id` path).
     *
     * @param userId the id of the buyer
     */
    fun findByBuyerId(userId: UUID): List<ExpenseEntity>
}
