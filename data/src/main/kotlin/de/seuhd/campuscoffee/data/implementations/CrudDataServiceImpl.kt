package de.seuhd.campuscoffee.data.implementations

import de.seuhd.campuscoffee.data.mapper.EntityMapper
import de.seuhd.campuscoffee.data.persistence.ConstraintMapping
import de.seuhd.campuscoffee.data.persistence.entities.Entity
import de.seuhd.campuscoffee.domain.exceptions.ConcurrentUpdateException
import de.seuhd.campuscoffee.domain.exceptions.DeletionConflictException
import de.seuhd.campuscoffee.domain.exceptions.DuplicationException
import de.seuhd.campuscoffee.domain.exceptions.NotFoundException
import de.seuhd.campuscoffee.domain.model.DomainModel
import de.seuhd.campuscoffee.domain.ports.data.CrudDataService
import de.seuhd.campuscoffee.domain.ports.system.IdGeneratorService
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.transaction.annotation.Transactional

/**
 * Base implementation of CRUD data service operations, providing common functionality reused across
 * entity types. Subclasses supply the repository, mapper, domain class, and unique-constraint
 * mappings via the constructor; this is the data-layer adapter for the domain's port interface.
 *
 * @param DOMAIN     the domain model type
 * @param ENTITY     the JPA entity type
 * @param REPOSITORY the repository type (a JpaRepository over the entity)
 * @param ID         the type of the unique identifier (e.g., Long, UUID, String)
 */
abstract class CrudDataServiceImpl<DOMAIN : DomainModel<ID>, ENTITY : Entity, REPOSITORY, ID : Any>(
    protected val repository: REPOSITORY,
    protected val mapper: EntityMapper<DOMAIN, ENTITY>,
    protected val domainClass: Class<DOMAIN>,
    /**
     * The entity's unique constraints, declared by the subclass. Each maps a database constraint name
     * to the domain field it guards, so a uniqueness violation is reported as a [DuplicationException]
     * on that field.
     */
    protected val uniqueConstraints: Set<ConstraintMapping<DOMAIN>>,
    /** Generates the id for a new entity. */
    private val idGenerator: IdGeneratorService
) : CrudDataService<DOMAIN, ID>
    where REPOSITORY : JpaRepository<ENTITY, ID> {
    override fun clear() {
        repository.deleteAllInBatch()
        repository.flush()
    }

    override fun getAll(): List<DOMAIN> = repository.findAll().map { mapper.fromEntity(it) }

    override fun getById(id: ID): DOMAIN =
        repository.findByIdOrNull(id)?.let { mapper.fromEntity(it) } ?: throw NotFoundException(domainClass, id)

    /**
     * Upserts a domain object, converting database uniqueness violations into [DuplicationException]
     * via the subclass-declared constraint mappings. Timestamps are managed by the JPA lifecycle
     * callbacks.
     *
     * @throws DuplicationException if a declared uniqueness constraint is violated
     * @throws DataIntegrityViolationException if an unhandled constraint violation occurs
     */
    override fun upsert(domain: DOMAIN): DOMAIN {
        try {
            val id = domain.id
            if (id == null) {
                // new entity (no id yet): assign an id, then insert
                val entity = mapper.toEntity(domain)
                entity.id = idGenerator.newId()
                return mapper.fromEntity(repository.saveAndFlush(entity))
            }

            // update existing entity (timestamps are set by the @PreUpdate callback)
            val entity = repository.findByIdOrNull(id) ?: throw NotFoundException(domainClass, id)
            mapper.updateEntity(domain, entity)
            return mapper.fromEntity(repository.saveAndFlush(entity))
        } catch (e: OptimisticLockingFailureException) {
            // the row changed between the read above and this write; surface it as a domain conflict,
            // keeping the original optimistic locking failure as the cause
            throw ConcurrentUpdateException(domainClass, domain.id, e)
        } catch (e: DataIntegrityViolationException) {
            // the database reports which named constraint was violated; map it to the declared domain field
            val violated = ConstraintMapping.constraintNameOf(e)
            if (violated != null) {
                for (constraint in uniqueConstraints) {
                    if (violated.equals(constraint.constraintName, ignoreCase = true)) {
                        throw DuplicationException(
                            domainClass,
                            constraint.columnName,
                            "${constraint.extractValue(domain)}"
                        )
                    }
                }
            }
            // no declared unique constraint matched (e.g., a CHECK or foreign key violation) -> rethrow
            throw e
        }
    }

    /**
     * Deletes by id, translating a foreign key violation (other data still references the entity) into
     * a [DeletionConflictException]. The explicit flush surfaces the violation inside this method, and
     * thus inside the catch, instead of at the transaction commit.
     *
     * @throws NotFoundException if no entity with the given id exists
     * @throws DeletionConflictException if other data still references the entity
     */
    @Transactional
    override fun delete(id: ID) {
        if (!repository.existsById(id)) {
            throw NotFoundException(domainClass, id)
        }
        try {
            repository.deleteById(id)
            repository.flush()
        } catch (e: DataIntegrityViolationException) {
            throw DeletionConflictException(domainClass, id, e)
        }
    }

    /**
     * Queries by a unique field following the common pattern: query -> map -> orElseThrow. Reduces
     * duplication across data services that look up entities by a unique field other than the id.
     *
     * @throws NotFoundException if no entity matches the query
     */
    protected fun findByFieldOrThrow(
        queryFunction: () -> ENTITY?,
        fieldName: String,
        fieldValue: String
    ): DOMAIN =
        queryFunction()?.let { mapper.fromEntity(it) } ?: throw NotFoundException(domainClass, fieldName, fieldValue)
}
