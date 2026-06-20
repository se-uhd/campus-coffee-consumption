package de.seuhd.campuscoffee.domain.implementation

import de.seuhd.campuscoffee.domain.exceptions.DuplicationException
import de.seuhd.campuscoffee.domain.model.DomainModel
import de.seuhd.campuscoffee.domain.ports.api.CrudService
import de.seuhd.campuscoffee.domain.ports.data.CrudDataService
import org.slf4j.LoggerFactory
import org.springframework.transaction.annotation.Transactional

/**
 * Abstract base implementation of CRUD service operations, providing common functionality
 * reused across services (template method pattern). Subclasses supply the data service and the
 * domain class via the constructor and the [dataService] method.
 *
 * @param DOMAIN the domain model type
 * @param ID     the type of the unique identifier (e.g., Long, UUID, String)
 * @param domainClass the domain class type, used for exception and log messages
 */
abstract class CrudServiceImpl<DOMAIN : DomainModel<ID>, ID>(
    protected val domainClass: Class<DOMAIN>
) : CrudService<DOMAIN, ID> {
    /** The data service used for the actual persistence operations. */
    protected abstract fun dataService(): CrudDataService<DOMAIN, ID>

    override fun clear() {
        log.warn("Clearing all {} data...", domainClass.simpleName)
        dataService().clear()
    }

    override fun getAll(): List<DOMAIN> {
        log.debug("Retrieving all {}...", domainClass.simpleName)
        return dataService().getAll()
    }

    override fun getById(id: ID): DOMAIN {
        log.debug("Retrieving {} with ID '{}'...", domainClass.simpleName, id)
        return dataService().getById(id)
    }

    /**
     * Performs the upsert with consistent error handling and logging. The database constraint
     * enforces uniqueness (the data layer throws [DuplicationException] on a violation), and the
     * JPA lifecycle callbacks set the timestamps.
     */
    @Transactional
    override fun upsert(domainObject: DOMAIN): DOMAIN {
        val id = domainObject.id
        if (id == null) {
            log.info("Creating new {}...", domainClass.simpleName)
        } else {
            log.info("Updating {} with ID '{}'...", domainClass.simpleName, id)
            // the entity must exist in the database before the update
            dataService().getById(id)
        }

        try {
            val upserted = dataService().upsert(domainObject)
            log.info("Successfully upserted {} with ID: '{}'.", domainClass.simpleName, upserted.id)
            return upserted
        } catch (e: DuplicationException) {
            log.error("Error upserting {}: {}", domainClass.simpleName, e.message)
            throw e
        }
    }

    override fun delete(id: ID) {
        log.info("Trying to delete {} with ID '{}'...", domainClass.simpleName, id)
        dataService().delete(id)
        log.info("{} with ID {} deleted.", domainClass.simpleName, id)
    }

    private companion object {
        private val log = LoggerFactory.getLogger(CrudServiceImpl::class.java)
    }
}
