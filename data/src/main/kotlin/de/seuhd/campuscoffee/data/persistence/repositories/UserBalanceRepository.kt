package de.seuhd.campuscoffee.data.persistence.repositories

import de.seuhd.campuscoffee.data.persistence.entities.UserBalanceEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

/**
 * Repository for the per-user balance projection. Keyed by the user id; a `save` upserts the user's
 * balance (the id is assigned, so a save merges).
 */
interface UserBalanceRepository : JpaRepository<UserBalanceEntity, UUID>
