package de.seuhd.campuscoffee.data.persistence.repositories

import de.seuhd.campuscoffee.data.persistence.entities.PaymentEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

/**
 * Repository for persisting payment entities.
 */
interface PaymentRepository : JpaRepository<PaymentEntity, UUID> {
    /**
     * Returns all payments whose user is the one with the given id, that user's deposits (Spring
     * Data derives the query from the nested `user.id` path). Pure kitty adjustments have no user and are
     * not returned.
     *
     * @param userId the id of the user
     */
    fun findByUserId(userId: UUID): List<PaymentEntity>
}
