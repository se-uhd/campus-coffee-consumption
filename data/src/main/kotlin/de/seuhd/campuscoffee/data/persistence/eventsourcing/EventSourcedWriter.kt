package de.seuhd.campuscoffee.data.persistence.eventsourcing

import de.seuhd.campuscoffee.domain.model.DomainModel
import de.seuhd.campuscoffee.domain.ports.IdGenerator
import org.springframework.stereotype.Component
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID
import kotlin.reflect.KClass

/**
 * The shared event-first logic the event sourcing data-service decorators use for their mutating
 * operations (create, update, delete, clear). Each operation assigns the id and timestamps so the event
 * body is complete, appends the event, then projects it onto the read tables, all within the caller's
 * transaction (the decorator methods are `@Transactional`). If the projection violates a constraint it
 * throws and the whole transaction rolls back, so the log never keeps an invalid event.
 *
 * The decorators pass the per-type steps in as lambdas (how to build the domain object via `copy`, and how
 * to read one back), so this holds no per-type knowledge. Ids come from the primary [IdGenerator], so the
 * assigned entity ids are produced by the same generator the relational delegate (the read model) uses.
 */
@Component
class EventSourcedWriter(
    private val eventStore: EventStore,
    private val projector: ReadModelProjector,
    private val idGenerator: IdGenerator
) {
    /**
     * Creates (no id) or updates (id present) a domain object. On create it assigns a new id and both
     * timestamps; on update it loads the current row (a missing one throws [NotFoundException]
     * [de.seuhd.campuscoffee.domain.exceptions.NotFoundException]), keeps its `createdAt`, and sets a new
     * `updatedAt`. Returns the projected row, read back through [getById].
     *
     * @param domain the domain object to create (no id) or update (id present)
     * @param getById reads a domain object back by its id (used on update and to return the projected row)
     * @param buildForInsert builds the complete object for a create, given the new id and the timestamp
     * @param buildForUpdate builds the complete object for an update, given the existing object and the timestamp
     */
    fun <D : DomainModel<UUID>> upsert(
        domain: D,
        getById: (UUID) -> D,
        buildForInsert: (id: UUID, now: LocalDateTime) -> D,
        buildForUpdate: (existing: D, now: LocalDateTime) -> D
    ): D {
        val now = now()
        val existingId = domain.id
        val complete: D
        val event: EventEntity
        if (existingId == null) {
            complete = buildForInsert(idGenerator.newId(), now)
            event = eventStore.appendInsert(complete)
        } else {
            complete = buildForUpdate(getById(existingId), now)
            event = eventStore.appendUpdate(complete)
        }
        project(event)
        return getById(requireNotNull(complete.id) { "The built domain object must have an id." })
    }

    /**
     * Deletes a domain object. Loads it first (a missing one throws [NotFoundException]
     * [de.seuhd.campuscoffee.domain.exceptions.NotFoundException], matching the relational adapter), then
     * appends the DELETE event and projects the removal (a still-referenced row throws
     * [DeletionConflictException][de.seuhd.campuscoffee.domain.exceptions.DeletionConflictException]).
     *
     * The DELETE event carries the id, plus any owner-key body fields [ownerKeys] derives from the loaded
     * object, so a deleted expense or settlement is still matched to its owner by the member/kitty ledger
     * reads (which key on `buyerUserId`/`userId`) and the deletion is reversed there.
     *
     * @param domainType the domain type of the object to delete
     * @param id the id of the object to delete
     * @param loadForExistence loads the object by id first, so a missing one throws before the DELETE is appended
     * @param ownerKeys derives the owner-key body fields from the loaded object (none by default)
     */
    fun <D : Any> delete(
        domainType: KClass<out DomainModel<*>>,
        id: UUID,
        loadForExistence: (UUID) -> D,
        ownerKeys: (D) -> Map<String, Any?> = { emptyMap() }
    ) {
        val loaded = loadForExistence(id)
        project(eventStore.appendDelete(domainType, id, ownerKeys(loaded)))
    }

    /**
     * Clears a type: removes its events and clears its read table through the relational delegate.
     *
     * @param domainType the domain type whose events are removed
     * @param clearReadModel the callback that clears the type's read table
     */
    fun clear(
        domainType: KClass<out DomainModel<*>>,
        clearReadModel: () -> Unit
    ) {
        eventStore.clear(eventStore.entityTypeOf(domainType))
        clearReadModel()
    }

    /** Projects an appended event onto the read tables via the [ReadModelProjector]. */
    private fun project(event: EventEntity) = projector.apply(event)

    /** The current UTC timestamp used for the assigned `createdAt`/`updatedAt`. */
    private fun now() = LocalDateTime.now(UTC)

    private companion object {
        private val UTC = ZoneId.of("UTC")
    }
}
