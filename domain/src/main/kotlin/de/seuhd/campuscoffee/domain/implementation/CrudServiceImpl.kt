package de.seuhd.campuscoffee.domain.implementation

import de.seuhd.campuscoffee.domain.exceptions.DuplicationException
import de.seuhd.campuscoffee.domain.model.DomainModel
import de.seuhd.campuscoffee.domain.ports.api.CrudService
import de.seuhd.campuscoffee.domain.ports.data.CrudDataService
import io.github.oshai.kotlinlogging.KotlinLogging
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
        log.warn { "Clearing all ${domainClass.simpleName} data." }
        dataService().clear()
    }

    override fun getAll(): List<DOMAIN> {
        log.debug { "Retrieving all ${domainClass.simpleName}." }
        return dataService().getAll()
    }

    override fun getById(id: ID): DOMAIN {
        log.debug { "Retrieving ${domainClass.simpleName} with id '$id'." }
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
            log.debug { "Creating a new ${domainClass.simpleName}." }
        } else {
            // no existence pre-read here: the data adapter's update path reads the row by id and throws
            // NotFoundException itself if it is missing, so a pre-read would only duplicate that lookup
            log.debug { "Updating ${domainClass.simpleName} with id '$id'." }
        }

        try {
            val upserted = dataService().upsert(domainObject)
            log.debug { "Upserted ${domainClass.simpleName} with id '${upserted.id}'." }
            return upserted
        } catch (e: DuplicationException) {
            log.warn { "Failed to upsert ${domainClass.simpleName}: ${e.message}" }
            throw e
        }
    }

    override fun delete(id: ID) {
        log.debug { "Deleting ${domainClass.simpleName} with id '$id'." }
        dataService().delete(id)
        log.info { "Deleted ${domainClass.simpleName} with id '$id'." }
    }

    private companion object {
        private val log = KotlinLogging.logger {}
    }
}
