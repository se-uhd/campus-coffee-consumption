package de.seuhd.campuscoffee.data.persistence.repositories

import de.seuhd.campuscoffee.data.persistence.entities.CoffeePriceEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

/**
 * Repository for persisting the coffee-price entity. There is at most one row (the global price singleton),
 * so the single current row is read through the inherited `findAll()`.
 */
interface CoffeePriceRepository : JpaRepository<CoffeePriceEntity, UUID>
