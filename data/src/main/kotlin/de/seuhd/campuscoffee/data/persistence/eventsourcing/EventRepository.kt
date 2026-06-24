package de.seuhd.campuscoffee.data.persistence.eventsourcing

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

/**
 * Repository for the append-only event log.
 */
interface EventRepository : JpaRepository<EventEntity, UUID> {
    /** All events in append order (by the monotonic [EventEntity.seq]), for replaying the whole log. */
    fun findAllByOrderBySeqAsc(): List<EventEntity>

    /**
     * Returns the events for one domain object (matched by the id embedded in the body) of a given type,
     * newest first, for reading an entity's history from the log. A native query because the match is on
     * the `jsonb` body's `id` (indexed by `idx_events_body_id`), which JPQL cannot express.
     *
     * @param entityType the entity type label (the [LoggedEntityType] label stored in `entity_type`)
     * @param bodyId     the domain object's id as it appears in the body (its string form)
     * @param limit      the maximum number of events to return
     * @param offset     the number of events to skip from the newest (for paging)
     */
    @Query(
        value =
            "SELECT * FROM events WHERE entity_type = :entityType AND body ->> 'id' = :bodyId " +
                "ORDER BY seq DESC LIMIT :limit OFFSET :offset",
        nativeQuery = true
    )
    fun findHistory(
        @Param("entityType") entityType: String,
        @Param("bodyId") bodyId: String,
        @Param("limit") limit: Int,
        @Param("offset") offset: Int
    ): List<EventEntity>

    /**
     * Returns every event of the given type in append order, for reading a whole stream (e.g. the price
     * history, or one side of the kitty history).
     *
     * @param entityType the entity type label (the [LoggedEntityType] label)
     */
    fun findByEntityTypeOrderBySeqAsc(entityType: String): List<EventEntity>

    /**
     * Returns a member's full unified-activity stream in append order: their consumption events, the expenses
     * they bought, and the deposits they paid. Keyed on the owning user id embedded in each body
     * (`userId` for consumptions and payments, `buyerUserId` for expenses), so it survives a consumption row
     * being recreated. A native query because the match is on the `jsonb` body (the V7 owner-key indexes).
     *
     * @param userId the owning member's id (its string form)
     */
    @Query(
        value =
            "SELECT * FROM events WHERE " +
                "(entity_type = 'CoffeeConsumption' AND body ->> 'userId' = :userId) OR " +
                "(entity_type = 'Expense' AND body ->> 'buyerUserId' = :userId) OR " +
                "(entity_type = 'Payment' AND body ->> 'userId' = :userId) " +
                "ORDER BY seq ASC",
        nativeQuery = true
    )
    fun findUserActivity(
        @Param("userId") userId: String
    ): List<EventEntity>

    /**
     * Returns the whole kitty money stream in append order: every payment (a deposit or a kitty
     * adjustment) and every expense, ordered by `seq` in SQL so the kitty walk reads one ordered stream
     * instead of concatenating two type streams and re-sorting them in memory. The `(entity_type, seq)`
     * index (V8) serves this directly.
     */
    @Query(
        value = "SELECT * FROM events WHERE entity_type IN ('Payment', 'Expense') ORDER BY seq ASC",
        nativeQuery = true
    )
    fun findKittyStream(): List<EventEntity>

    /**
     * Whether the log already holds at least one event for the given domain type, so the import can skip it.
     *
     * @param entityType the entity type label (the [LoggedEntityType] label stored in `entity_type`)
     */
    fun existsByEntityType(entityType: String): Boolean

    /**
     * Removes every event for the given domain type, when clearing that type's data.
     *
     * @param entityType the entity type label (the [LoggedEntityType] label stored in `entity_type`)
     */
    fun deleteByEntityType(entityType: String)
}
