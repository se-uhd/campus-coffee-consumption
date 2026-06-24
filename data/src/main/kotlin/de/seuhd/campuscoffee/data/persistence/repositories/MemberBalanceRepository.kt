package de.seuhd.campuscoffee.data.persistence.repositories

import de.seuhd.campuscoffee.data.persistence.entities.MemberBalanceEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

/**
 * Repository for the per-member balance projection. Keyed by the member id; a `save` upserts the member's
 * balance (the id is assigned, so a save merges).
 */
interface MemberBalanceRepository : JpaRepository<MemberBalanceEntity, UUID>
