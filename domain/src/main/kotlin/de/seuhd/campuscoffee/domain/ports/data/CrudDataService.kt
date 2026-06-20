package de.seuhd.campuscoffee.domain.ports.data

import de.seuhd.campuscoffee.domain.exceptions.DeletionConflictException
import de.seuhd.campuscoffee.domain.exceptions.NotFoundException
import de.seuhd.campuscoffee.domain.model.objects.DomainModel

/**
 * Generic port interface for CRUD (Create, Read, Update, Delete) data operations.
 *
 * This port is implemented by the data layer (adapter) and defines the contract
 * for basic persistence operations on entities. Follows the hexagonal architecture
 * pattern where the domain defines the port and the data layer provides the adapter.
 *
 * @param DOMAIN the domain model type
 * @param ID     the type of the unique identifier (e.g., Long, UUID, String)
 */
interface CrudDataService<DOMAIN : DomainModel<ID>, ID> {
    /**
     * Clears all data of this type from the data store.
     * Warning: This operation is destructive and cannot be undone.
     */
    fun clear()

    /**
     * Retrieves all entities from the data store and returns them as domain objects.
     *
     * @return a list of all entities; never null, but may be empty
     */
    fun getAll(): List<DOMAIN>

    /**
     * Retrieves a single entity by its unique identifier and returns it as a domain object.
     *
     * @param id the unique identifier of the entity to retrieve
     * @return the entity with the specified ID
     * @throws NotFoundException if no entity exists with the given ID
     */
    fun getById(id: ID): DOMAIN

    /**
     * Creates a new entity or updates an existing one.
     * If the entity has an ID and exists, it is updated; if it has no ID, a new entity is created.
     *
     * @param domain the domain object to create or update
     * @return the persisted entity with updated timestamps and ID as a domain object
     * @throws NotFoundException if attempting to update an entity that does not exist
     */
    fun upsert(domain: DOMAIN): DOMAIN

    /**
     * Deletes an entity by its unique identifier.
     *
     * @param id the unique identifier of the entity to delete
     * @throws NotFoundException if no entity exists with the given ID
     * @throws DeletionConflictException if other data still references the entity
     */
    fun delete(id: ID)
}
