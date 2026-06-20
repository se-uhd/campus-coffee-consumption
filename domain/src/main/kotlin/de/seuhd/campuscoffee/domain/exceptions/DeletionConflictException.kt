package de.seuhd.campuscoffee.domain.exceptions

/**
 * Thrown when an entity cannot be deleted because other data still references it. The caller must delete
 * the referencing data first.
 *
 * @param domainClass class of the domain object (e.g., "User", "CoffeeConsumption")
 * @param id          the ID of the entity that could not be deleted
 * @param cause       the underlying integrity violation, if any
 */
class DeletionConflictException(
    domainClass: Class<*>,
    id: Any?,
    cause: Throwable? = null
) : RuntimeException(
        "${domainClass.simpleName} with ID $id cannot be deleted because other data references it.",
        cause
    )
