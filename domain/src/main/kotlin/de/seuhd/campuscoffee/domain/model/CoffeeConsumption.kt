package de.seuhd.campuscoffee.domain.model

import java.time.LocalDateTime
import java.util.UUID

/**
 * Immutable coffee-consumption domain model: a single member's running coffee count. It is modeled
 * exactly like a CampusCoffee review (it references a [user] the way a review referenced its author),
 * with the running [count] standing in for the review body. There is exactly one consumption per user.
 *
 * Every change to [count] (a member adding a coffee, a member undoing a recent one, or an admin override
 * correcting the count) is a plain upsert that the event-sourced data adapter records as a full-state event
 * in the append-only log; the per-member transaction history is read back from that log.
 *
 * Optimistic locking lives entirely in the data layer (the entity's `@Version` column), exactly as for
 * CampusCoffee's review: each change is a load-modify-save in one transaction, so two concurrent
 * self-scans either serialize or the loser gets a `ConcurrentUpdateException` (409) and retries. The
 * domain model therefore carries no version field.
 */
data class CoffeeConsumption(
    override val id: UUID? = null,
    val createdAt: LocalDateTime? = null,
    val updatedAt: LocalDateTime? = null,
    val user: User,
    val count: Int
) : DomainModel<UUID>
