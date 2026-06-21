package de.seuhd.campuscoffee.data.persistence.eventsourcing

import de.seuhd.campuscoffee.data.implementations.CrudDataServiceImpl
import de.seuhd.campuscoffee.data.mapper.CoffeeConsumptionEntityMapper
import de.seuhd.campuscoffee.data.mapper.CoffeePriceEntityMapper
import de.seuhd.campuscoffee.data.mapper.EntityMapper
import de.seuhd.campuscoffee.data.mapper.ExpenseEntityMapper
import de.seuhd.campuscoffee.data.mapper.PaymentEntityMapper
import de.seuhd.campuscoffee.data.mapper.UserEntityMapper
import de.seuhd.campuscoffee.data.persistence.entities.CoffeeConsumptionEntity
import de.seuhd.campuscoffee.data.persistence.entities.Entity
import de.seuhd.campuscoffee.data.persistence.entities.UserEntity
import de.seuhd.campuscoffee.data.persistence.repositories.CoffeeConsumptionRepository
import de.seuhd.campuscoffee.data.persistence.repositories.CoffeePriceRepository
import de.seuhd.campuscoffee.data.persistence.repositories.ExpenseRepository
import de.seuhd.campuscoffee.data.persistence.repositories.PaymentRepository
import de.seuhd.campuscoffee.data.persistence.repositories.UserRepository
import de.seuhd.campuscoffee.domain.exceptions.ConcurrentUpdateException
import de.seuhd.campuscoffee.domain.exceptions.DeletionConflictException
import de.seuhd.campuscoffee.domain.exceptions.DuplicationException
import de.seuhd.campuscoffee.domain.exceptions.NotFoundException
import de.seuhd.campuscoffee.domain.model.CoffeeConsumption
import de.seuhd.campuscoffee.domain.model.CoffeePrice
import de.seuhd.campuscoffee.domain.model.DomainModel
import de.seuhd.campuscoffee.domain.model.Expense
import de.seuhd.campuscoffee.domain.model.Payment
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
 * A reference is stored in the body as an id (the body is flattened): a consumption and a payment carry a
 * `userId`, an expense a `buyerUserId`. The id is resolved against the already-projected user read model,
 * which is why the log records a user before anything that references it. A pure kitty adjustment carries a
 * null `userId` and resolves to no user.
 *
 * The dispatch is a `when` over [LoggedEntityType], so the compiler forces a branch for every logged type;
 * the shared steps (writing a row, loading-and-updating one, deleting one, translating violations) are
 * extracted into helpers. Hence the `TooManyFunctions` suppression.
 */
@Suppress("TooManyFunctions")
@Component
class ReadModelProjector(
    private val userRepository: UserRepository,
    private val coffeeConsumptionRepository: CoffeeConsumptionRepository,
    private val coffeePriceRepository: CoffeePriceRepository,
    private val expenseRepository: ExpenseRepository,
    private val paymentRepository: PaymentRepository,
    private val userMapper: UserEntityMapper,
    private val coffeeConsumptionMapper: CoffeeConsumptionEntityMapper,
    private val coffeePriceMapper: CoffeePriceEntityMapper,
    private val expenseMapper: ExpenseEntityMapper,
    private val paymentMapper: PaymentEntityMapper
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
     * @param entityType the entity type label (resolved to a [LoggedEntityType])
     * @param body the JSON body of the event
     */
    fun apply(
        changeType: ChangeType,
        entityType: String,
        body: Map<String, Any?>
    ) {
        val type = LoggedEntityType.ofLabel(entityType)
        translatingViolations(changeType, type, body) {
            when (changeType) {
                ChangeType.INSERT -> insert(type, body)
                ChangeType.UPDATE -> update(type, body)
                ChangeType.DELETE -> delete(type, body)
            }
        }
    }

    /** Inserts a new read model row for the event's type, mapping the body to the entity. */
    private fun insert(
        type: LoggedEntityType,
        body: Map<String, Any?>
    ) = when (type) {
        LoggedEntityType.USER ->
            insertRow(userRepository, userMapper.toEntity(convert(body, User::class)), body)
        LoggedEntityType.COFFEE_CONSUMPTION ->
            insertRow(coffeeConsumptionRepository, coffeeConsumptionMapper.toEntity(reconstructConsumption(body)), body)
        LoggedEntityType.COFFEE_PRICE ->
            insertRow(coffeePriceRepository, coffeePriceMapper.toEntity(convert(body, CoffeePrice::class)), body)
        LoggedEntityType.EXPENSE ->
            insertRow(expenseRepository, expenseMapper.toEntity(reconstructExpense(body)), body)
        LoggedEntityType.PAYMENT ->
            insertRow(paymentRepository, paymentMapper.toEntity(reconstructPayment(body)), body)
    }

    /** Updates the existing read model row for the event's type from the body. */
    private fun update(
        type: LoggedEntityType,
        body: Map<String, Any?>
    ) = when (type) {
        LoggedEntityType.USER ->
            updateRow(userRepository, userMapper, convert(body, User::class), body, User::class.java)
        LoggedEntityType.COFFEE_CONSUMPTION ->
            updateRow(
                coffeeConsumptionRepository,
                coffeeConsumptionMapper,
                reconstructConsumption(body),
                body,
                CoffeeConsumption::class.java
            )
        LoggedEntityType.COFFEE_PRICE ->
            updateRow(
                coffeePriceRepository,
                coffeePriceMapper,
                convert(body, CoffeePrice::class),
                body,
                CoffeePrice::class.java
            )
        LoggedEntityType.EXPENSE ->
            updateRow(expenseRepository, expenseMapper, reconstructExpense(body), body, Expense::class.java)
        LoggedEntityType.PAYMENT ->
            updateRow(paymentRepository, paymentMapper, reconstructPayment(body), body, Payment::class.java)
    }

    /** Removes the read model row identified by the body's id for the event's type. */
    private fun delete(
        type: LoggedEntityType,
        body: Map<String, Any?>
    ) {
        val id = requireNotNull(idOrNull(body)) { "A DELETE event must carry an id." }
        return when (type) {
            LoggedEntityType.USER -> deleteUserRow(id)
            LoggedEntityType.COFFEE_CONSUMPTION -> deleteRow(coffeeConsumptionRepository, id)
            LoggedEntityType.COFFEE_PRICE -> deleteRow(coffeePriceRepository, id)
            LoggedEntityType.EXPENSE -> deleteRow(expenseRepository, id)
            LoggedEntityType.PAYMENT -> deleteRow(paymentRepository, id)
        }
    }

    /**
     * Deletes a user read row, first removing the dependent consumption read row (the database FK is
     * `ON DELETE CASCADE`). Removing the consumption explicitly keeps the read model consistent and, in a
     * read-after-load session, avoids a flush of a managed consumption entity that would still reference the
     * just-deleted user. A foreign key violation from an expense or payment that still references the user
     * surfaces here as a [DeletionConflictException].
     */
    private fun deleteUserRow(id: UUID) {
        coffeeConsumptionRepository.findByUserId(id)?.let { coffeeConsumptionRepository.delete(it) }
        deleteRow(userRepository, id)
    }

    /** Rebuilds a [CoffeeConsumption] from its flattened body, resolving the user id against the read model. */
    private fun reconstructConsumption(body: Map<String, Any?>): CoffeeConsumption {
        val payload = convert(body, CoffeeConsumptionEventPayload::class)
        return CoffeeConsumption(
            id = payload.id,
            createdAt = payload.createdAt,
            updatedAt = payload.updatedAt,
            user = requireUser(payload.userId),
            count = payload.count
        )
    }

    /** Rebuilds an [Expense] from its flattened body, resolving the buyer id against the read model. */
    private fun reconstructExpense(body: Map<String, Any?>): Expense {
        val payload = convert(body, ExpenseEventPayload::class)
        return Expense(
            id = payload.id,
            createdAt = payload.createdAt,
            updatedAt = payload.updatedAt,
            buyer = requireUser(payload.buyerUserId),
            weightGrams = payload.weightGrams,
            amountCents = payload.amountCents,
            privateAmountCents = payload.privateAmountCents,
            kittyAmountCents = payload.kittyAmountCents,
            note = payload.note
        )
    }

    /** Rebuilds a [Payment] from its body, resolving the user id (null for a pure kitty adjustment). */
    private fun reconstructPayment(body: Map<String, Any?>): Payment {
        val payload = convert(body, PaymentEventPayload::class)
        return Payment(
            id = payload.id,
            createdAt = payload.createdAt,
            updatedAt = payload.updatedAt,
            user = payload.userId?.let { requireUser(it) },
            amountCents = payload.amountCents,
            note = payload.note
        )
    }

    /** Resolves a user id against the already-projected user read model, failing if the user is missing. */
    private fun requireUser(userId: UUID): User =
        userMapper.fromEntity(
            userRepository.findByIdOrNull(userId) ?: throw NotFoundException(User::class.java, userId)
        )

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
        type: LoggedEntityType,
        body: Map<String, Any?>,
        block: () -> Unit
    ) {
        try {
            block()
        } catch (e: OptimisticLockingFailureException) {
            throw ConcurrentUpdateException(type.domainClass.java, idOrNull(body), e)
        } catch (e: DataIntegrityViolationException) {
            throw integrityExceptionFor(changeType, type, body, e)
        }
    }

    /**
     * The domain exception a relational integrity violation maps to: a foreign key violation on a delete is
     * a [DeletionConflictException], a violated unique constraint is a [DuplicationException], and anything
     * else propagates unchanged.
     */
    private fun integrityExceptionFor(
        changeType: ChangeType,
        type: LoggedEntityType,
        body: Map<String, Any?>,
        exception: DataIntegrityViolationException
    ): RuntimeException =
        if (changeType == ChangeType.DELETE) {
            DeletionConflictException(type.domainClass.java, idOrNull(body), exception)
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

    /** The flattened payload of an expense event (its buyer is stored as an id). */
    private data class ExpenseEventPayload(
        val id: UUID,
        val createdAt: LocalDateTime,
        val updatedAt: LocalDateTime,
        val buyerUserId: UUID,
        val weightGrams: Int,
        val amountCents: Int,
        val privateAmountCents: Int,
        val kittyAmountCents: Int,
        val note: String?
    )

    /** The flattened payload of a payment event (its user is stored as a nullable id). */
    private data class PaymentEventPayload(
        val id: UUID,
        val createdAt: LocalDateTime,
        val updatedAt: LocalDateTime,
        val userId: UUID?,
        val amountCents: Int,
        val note: String?
    )

    private companion object {
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
