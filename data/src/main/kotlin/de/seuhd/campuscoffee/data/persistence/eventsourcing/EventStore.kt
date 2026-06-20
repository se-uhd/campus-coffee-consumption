package de.seuhd.campuscoffee.data.persistence.eventsourcing

import de.seuhd.campuscoffee.data.configuration.IdGeneratorConfiguration
import de.seuhd.campuscoffee.domain.model.objects.DomainModel
import de.seuhd.campuscoffee.domain.ports.ActorProvider
import de.seuhd.campuscoffee.domain.ports.ChangeNoteContext
import de.seuhd.campuscoffee.domain.ports.IdGenerator
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import tools.jackson.core.type.TypeReference
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID
import kotlin.reflect.KClass

/**
 * Appends events to the log. In event sourcing mode the log is the source of truth; projecting the event
 * onto the read tables is a separate step ([ReadModelProjector]) the caller runs in the same transaction.
 *
 * The body is the full JSON state of the domain object (INSERT/UPDATE), or just its id (DELETE), built with
 * [EventJsonMapper] so it matches the `jsonb` column. The domain object's own id is inside the body; the
 * event's own id comes from a dedicated [IdGenerator] bean, qualified
 * [IdGeneratorConfiguration.EVENT_ID_GENERATOR], with its own seed, separate from the entity-id generator.
 *
 * Each event is also stamped with two metadata fields sourced from request-scoped context ports: the
 * actor's login (from [ActorProvider]) as `created_by`, and an optional admin note (from
 * [ChangeNoteContext]) as `note`. Neither is part of the JSON body.
 */
@Service
class EventStore(
    private val eventRepository: EventRepository,
    @param:Qualifier(IdGeneratorConfiguration.EVENT_ID_GENERATOR) private val idGenerator: IdGenerator,
    private val actorProvider: ActorProvider,
    private val changeNoteContext: ChangeNoteContext
) {
    /**
     * Appends an INSERT event carrying the full state of a newly created domain object.
     *
     * @param domain the newly created domain object whose full state is recorded
     */
    fun appendInsert(domain: DomainModel<*>): EventEntity =
        append(ChangeType.INSERT, entityTypeOf(domain), toBody(domain))

    /**
     * Appends an UPDATE event carrying the full new state of a modified domain object.
     *
     * @param domain the modified domain object whose full new state is recorded
     */
    fun appendUpdate(domain: DomainModel<*>): EventEntity =
        append(ChangeType.UPDATE, entityTypeOf(domain), toBody(domain))

    /**
     * Appends a DELETE event carrying only the id of the removed domain object.
     *
     * @param domainType the domain type of the removed object
     * @param id the id of the removed domain object
     */
    fun appendDelete(
        domainType: KClass<out DomainModel<*>>,
        id: UUID
    ): EventEntity = append(ChangeType.DELETE, entityTypeOf(domainType), mapOf("id" to id.toString()))

    /**
     * Removes every event for the given domain type; its read table is cleared separately.
     *
     * @param entityType the entity type label (the domain class's simple name)
     */
    fun clear(entityType: String) = eventRepository.deleteByEntityType(entityType)

    /**
     * Whether the log already holds an event for the given domain type, so the import can skip that type.
     *
     * @param entityType the entity type label (the domain class's simple name)
     */
    fun hasEventsFor(entityType: String): Boolean = eventRepository.existsByEntityType(entityType)

    /**
     * The event's entity type label, the domain class's simple name (`User`, `CoffeeConsumption`).
     *
     * @param domain the domain object whose type label is derived
     */
    fun entityTypeOf(domain: DomainModel<*>): String = entityTypeOf(domain::class)

    /**
     * The entity type label for a domain type, its simple name (`User`, `CoffeeConsumption`).
     *
     * @param domainType the domain type whose label is derived
     */
    fun entityTypeOf(domainType: KClass<out DomainModel<*>>): String =
        requireNotNull(domainType.simpleName) { "A domain type used for an event must have a simple name." }

    /** Builds the event, assigns its own id, version, and timestamp, and flushes it before the projection. */
    private fun append(
        changeType: ChangeType,
        entityType: String,
        body: Map<String, Any?>
    ): EventEntity {
        val event =
            EventEntity().apply {
                id = idGenerator.newId()
                this.changeType = changeType
                this.entityType = entityType
                entityVersion = PAYLOAD_SCHEMA_VERSION
                this.body = body
                createdAt = LocalDateTime.now(UTC)
                createdBy = actorProvider.currentActor()
                note = changeNoteContext.currentNote()
            }
        // flush the event before the projection runs; if the projection then fails, the transaction rolls
        // back the event together with the projection, so the log never keeps an invalid event
        return eventRepository.saveAndFlush(event)
    }

    /** Serializes a domain object to the JSON body map via [EventJsonMapper] (matching the `jsonb` column). */
    private fun toBody(domain: DomainModel<*>): Map<String, Any?> =
        EventJsonMapper.instance.convertValue(domain, BODY_TYPE)

    companion object {
        /** The event payload schema version recorded on every event; increment it if the body format changes. */
        const val PAYLOAD_SCHEMA_VERSION = 1L

        private val BODY_TYPE = object : TypeReference<Map<String, Any?>>() {}
        private val UTC = ZoneId.of("UTC")
    }
}
