package de.seuhd.campuscoffee.data.persistence.eventsourcing

import de.seuhd.campuscoffee.data.implementations.CrudDataServiceImpl
import de.seuhd.campuscoffee.data.mapper.CoffeeConsumptionEntityMapper
import de.seuhd.campuscoffee.data.mapper.EntityMapper
import de.seuhd.campuscoffee.data.mapper.UserEntityMapper
import de.seuhd.campuscoffee.data.persistence.entities.CoffeeConsumptionEntity
import de.seuhd.campuscoffee.data.persistence.entities.Entity
import de.seuhd.campuscoffee.data.persistence.entities.UserEntity
import de.seuhd.campuscoffee.data.persistence.repositories.CoffeeConsumptionRepository
import de.seuhd.campuscoffee.data.persistence.repositories.UserRepository
import de.seuhd.campuscoffee.domain.exceptions.ConcurrentUpdateException
import de.seuhd.campuscoffee.domain.exceptions.DeletionConflictException
import de.seuhd.campuscoffee.domain.exceptions.DuplicationException
import de.seuhd.campuscoffee.domain.exceptions.NotFoundException
import de.seuhd.campuscoffee.domain.model.CoffeeConsumption
import de.seuhd.campuscoffee.domain.model.DomainModel
import de.seuhd.campuscoffee.domain.model.User
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import java.time.LocalDateTime
import java.util.UUID
import kotlin.reflect.KClass

/**
 * Applies a single event to the relational read tables. This is the one place that writes the read model,
 * used both by the decorators when they apply a write and by the events-to-data replay, so a row written
 * while serving a request and a row rebuilt from the log go through identical code.
 *
 * It writes the id and `createdAt`/`updatedAt` from the event body, marking the entity so the
 * `@PrePersist`/`@PreUpdate` timestamp callbacks leave them as written (see
 * [Entity.markTimestampsPreassigned]). The relational tables still enforce the invariants: a uniqueness,
 * foreign key, or optimistic locking violation here rolls the whole transaction back (so the log never
 * keeps an invalid event) and surfaces as the same domain exception the relational adapter would throw.
 *
 * The user of a [CoffeeConsumption] arrives as an id (the body is flattened); it is resolved against the
 * already-projected user read model, which is why the log records a user before the consumption that
 * references it.
 *
 * The shared steps (writing a row, loading-and-updating one, deleting one, translating violations) are
 * extracted into helpers; the dispatch stays per type because the repository, the mapper, and (for a
 * consumption) the reference resolution differ by type. Hence the `TooManyFunctions` suppression.
 */
@Suppress("TooManyFunctions")
@Component
class ReadModelProjector(
    private val userRepository: UserRepository,
    private val coffeeConsumptionRepository: CoffeeConsumptionRepository,
    private val userMapper: UserEntityMapper,
    private val coffeeConsumptionMapper: CoffeeConsumptionEntityMapper
) {
    /**
     * Applies a stored event to the read tables, unwrapping its fields, which are always populated.
     *
     * @param event the stored event whose change type, entity type, and body are applied
     */
    fun apply(event: EventEntity) =
        apply(requireNotNull(event.changeType), requireNotNull(event.entityType), requireNotNull(event.body))

    /**
     * Applies one event (its change type, entity type, and JSON body) to the read tables.
     *
     * @param changeType the change type (INSERT, UPDATE, or DELETE)
     * @param entityType the entity type label (the domain class's simple name)
     * @param body the JSON body of the event
     */
    fun apply(
        changeType: ChangeType,
        entityType: String,
        body: Map<String, Any?>
    ) = translatingViolations(changeType, entityType, body) {
        when (changeType) {
            ChangeType.INSERT -> insert(entityType, body)
            ChangeType.UPDATE -> update(entityType, body)
            ChangeType.DELETE -> delete(entityType, body)
        }
    }

    /** Inserts a new read model row for the event's entity type, mapping the body to the entity. */
    private fun insert(
        entityType: String,
        body: Map<String, Any?>
    ) {
        when (entityType) {
            USER -> insertRow(userRepository, userMapper.toEntity(convert(body, User::class)), body)
            COFFEE_CONSUMPTION ->
                insertRow(
                    coffeeConsumptionRepository,
                    coffeeConsumptionMapper.toEntity(reconstructCoffeeConsumption(body)),
                    body
                )
            else -> unsupported(ChangeType.INSERT, entityType)
        }
    }

    /** Updates the existing read model row for the event's entity type from the body. */
    private fun update(
        entityType: String,
        body: Map<String, Any?>
    ) {
        when (entityType) {
            USER -> updateRow(userRepository, userMapper, convert(body, User::class), body, User::class.java)
            COFFEE_CONSUMPTION ->
                updateRow(
                    coffeeConsumptionRepository,
                    coffeeConsumptionMapper,
                    reconstructCoffeeConsumption(body),
                    body,
                    CoffeeConsumption::class.java
                )
            else -> unsupported(ChangeType.UPDATE, entityType)
        }
    }

    /** Removes the read model row identified by the body's id for the event's entity type. */
    private fun delete(
        entityType: String,
        body: Map<String, Any?>
    ) {
        val id = requireNotNull(idOrNull(body)) { "A DELETE event must carry an id." }
        when (entityType) {
            USER -> deleteRow(userRepository, id)
            COFFEE_CONSUMPTION -> deleteRow(coffeeConsumptionRepository, id)
            else -> unsupported(ChangeType.DELETE, entityType)
        }
    }

    /** Rebuilds a [CoffeeConsumption] from its flattened body, resolving the user id against the read model. */
    private fun reconstructCoffeeConsumption(body: Map<String, Any?>): CoffeeConsumption {
        val payload = convert(body, CoffeeConsumptionEventPayload::class)
        val user =
            userMapper.fromEntity(
                userRepository.findByIdOrNull(payload.userId)
                    ?: throw NotFoundException(User::class.java, payload.userId)
            )
        return CoffeeConsumption(
            id = payload.id,
            createdAt = payload.createdAt,
            updatedAt = payload.updatedAt,
            user = user,
            count = payload.count
        )
    }

    /** Saves a new row, writing the id and both timestamps from the body and stopping the `@PrePersist` callback. */
    private fun <E : Entity> insertRow(
        repository: JpaRepository<E, UUID>,
        entity: E,
        body: Map<String, Any?>
    ) {
        // write the id and both timestamps from the body; the flag stops @PrePersist from overwriting them
        entity.id = idOf(body)
        entity.createdAt = timestampOf(body, "createdAt")
        entity.updatedAt = timestampOf(body, "updatedAt")
        entity.markTimestampsPreassigned()
        repository.saveAndFlush(entity)
    }

    /** Loads the row (a missing one throws [NotFoundException]), updates it from the body, and keeps `createdAt`. */
    private fun <DOMAIN : DomainModel<*>, E : Entity> updateRow(
        repository: JpaRepository<E, UUID>,
        mapper: EntityMapper<DOMAIN, E>,
        domain: DOMAIN,
        body: Map<String, Any?>,
        domainClass: Class<out DomainModel<*>>
    ) {
        val id = idOf(body)
        val entity = repository.findByIdOrNull(id) ?: throw NotFoundException(domainClass, id)
        mapper.updateEntity(domain, entity)
        // the mapper leaves the timestamps alone; write the body's updatedAt and keep the original createdAt
        entity.updatedAt = timestampOf(body, "updatedAt")
        entity.markTimestampsPreassigned()
        repository.saveAndFlush(entity)
    }

    /** Deletes the row by id and flushes, so a foreign key violation surfaces here rather than at commit. */
    private fun <E : Entity> deleteRow(
        repository: JpaRepository<E, UUID>,
        id: UUID
    ) {
        repository.deleteById(id)
        // surface a foreign key violation here (inside translateViolations), not at transaction commit
        repository.flush()
    }

    /** Converts the JSON body map to the given type via [EventJsonMapper]. */
    private fun <T : Any> convert(
        body: Map<String, Any?>,
        type: KClass<T>
    ): T = EventJsonMapper.instance.convertValue(body, type.java)

    /**
     * Runs a projection step, translating the relational violations into the same domain exceptions the
     * relational adapter raises: an optimistic locking failure to [ConcurrentUpdateException], a
     * foreign key violation on a delete to [DeletionConflictException], and a uniqueness violation to
     * [DuplicationException]; any other integrity violation propagates unchanged.
     */
    private fun translatingViolations(
        changeType: ChangeType,
        entityType: String,
        body: Map<String, Any?>,
        block: () -> Unit
    ) {
        try {
            block()
        } catch (e: OptimisticLockingFailureException) {
            throw ConcurrentUpdateException(domainClassFor(entityType), idOrNull(body), e)
        } catch (e: DataIntegrityViolationException) {
            throw integrityExceptionFor(changeType, entityType, body, e)
        }
    }

    /**
     * The domain exception a relational integrity violation maps to: a foreign key violation on a delete is
     * a [DeletionConflictException], a violated unique constraint is a [DuplicationException], and anything
     * else propagates unchanged.
     */
    private fun integrityExceptionFor(
        changeType: ChangeType,
        entityType: String,
        body: Map<String, Any?>,
        exception: DataIntegrityViolationException
    ): RuntimeException =
        if (changeType == ChangeType.DELETE) {
            DeletionConflictException(domainClassFor(entityType), idOrNull(body), exception)
        } else {
            duplicationOrNull(exception, body) ?: exception
        }

    /** The [DuplicationException] for a violated unique constraint, or null when the violation is not a known one. */
    private fun duplicationOrNull(
        exception: DataIntegrityViolationException,
        body: Map<String, Any?>
    ): DuplicationException? {
        val rule =
            CrudDataServiceImpl.constraintNameOf(exception)?.lowercase()?.let { DUPLICATION_RULES[it] } ?: return null
        return DuplicationException(rule.domainClass, rule.field, rule.valueOf(body))
    }

    /** The domain class for an entity type label, used when building a translated exception. */
    private fun domainClassFor(entityType: String): Class<out DomainModel<*>> =
        DOMAIN_CLASSES[entityType] ?: error("Unknown entity type '$entityType'.")

    /** The body's id parsed to a [UUID], or null when the body carries none. */
    private fun idOrNull(body: Map<String, Any?>): UUID? = body["id"]?.let { UUID.fromString(it.toString()) }

    /** The body's id parsed to a [UUID], failing when the body carries none. */
    private fun idOf(body: Map<String, Any?>): UUID =
        requireNotNull(idOrNull(body)) { "An event body must carry an id." }

    /** Parses the body's timestamp at the given key to a [LocalDateTime]. */
    private fun timestampOf(
        body: Map<String, Any?>,
        key: String
    ): LocalDateTime = LocalDateTime.parse(requireNotNull(body[key]) { "An event body must carry $key." }.toString())

    /** Fails for a change type and entity type with no projection defined. */
    private fun unsupported(
        changeType: ChangeType,
        entityType: String
    ): Nothing = error("No projection defined for a $changeType of '$entityType'.")

    /** Maps a violated unique-constraint name to the domain class, field, and a value read from the body. */
    private class DuplicationRule(
        val domainClass: Class<out DomainModel<*>>,
        val field: String,
        val valueOf: (Map<String, Any?>) -> String
    )

    /** The flattened payload of a consumption event (its user is stored as an id). */
    private data class CoffeeConsumptionEventPayload(
        val id: UUID,
        val createdAt: LocalDateTime,
        val updatedAt: LocalDateTime,
        val userId: UUID,
        val count: Int
    )

    private companion object {
        // the event entity type labels, derived from the domain class names so they match EventStore
        private val USER = requireNotNull(User::class.simpleName)
        private val COFFEE_CONSUMPTION = requireNotNull(CoffeeConsumption::class.simpleName)

        // the domain class for each entity type label, for building the translated exceptions
        private val DOMAIN_CLASSES: Map<String, Class<out DomainModel<*>>> =
            mapOf(
                USER to User::class.java,
                COFFEE_CONSUMPTION to CoffeeConsumption::class.java
            )

        // the unique constraints whose violation maps to a DuplicationException, keyed by lowercase name
        private val DUPLICATION_RULES: Map<String, DuplicationRule> =
            mapOf(
                UserEntity.LOGIN_NAME_UNIQUE_CONSTRAINT to
                    DuplicationRule(User::class.java, UserEntity.LOGIN_NAME_COLUMN) { "${it["loginName"]}" },
                UserEntity.EMAIL_ADDRESS_UNIQUE_CONSTRAINT to
                    DuplicationRule(User::class.java, UserEntity.EMAIL_ADDRESS_COLUMN) { "${it["emailAddress"]}" },
                UserEntity.CAPABILITY_TOKEN_UNIQUE_CONSTRAINT to
                    DuplicationRule(
                        User::class.java,
                        UserEntity.CAPABILITY_TOKEN_COLUMN
                    ) { "${it["capabilityToken"]}" },
                CoffeeConsumptionEntity.USER_UNIQUE_CONSTRAINT to
                    DuplicationRule(CoffeeConsumption::class.java, "user_id") { "user ${it["userId"]}" }
            ).mapKeys { it.key.lowercase() }
    }
}
