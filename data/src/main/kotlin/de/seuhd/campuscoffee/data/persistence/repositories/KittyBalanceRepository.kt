package de.seuhd.campuscoffee.data.persistence.repositories

import de.seuhd.campuscoffee.data.persistence.entities.KittyBalanceEntity
import org.springframework.data.jpa.repository.JpaRepository

/**
 * Repository for the single-row kitty balance projection (keyed by the fixed
 * [KittyBalanceEntity.SINGLETON_ID]).
 */
interface KittyBalanceRepository : JpaRepository<KittyBalanceEntity, Int>
