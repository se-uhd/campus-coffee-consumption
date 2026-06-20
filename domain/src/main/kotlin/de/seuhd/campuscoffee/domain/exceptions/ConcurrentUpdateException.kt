package de.seuhd.campuscoffee.domain.exceptions

/**
 * Thrown when an update is rejected because the entity was modified concurrently since it was read
 * (an optimistic locking conflict). The caller should reload the current state and retry.
 *
 * @param domainClass class of the domain object (e.g., "CoffeeConsumption")
 * @param id          the ID of the entity that was modified concurrently
 * @param cause       the underlying optimistic locking failure, if any
 */
class ConcurrentUpdateException(
    domainClass: Class<*>,
    id: Any?,
    cause: Throwable? = null
) : RuntimeException(
        "${domainClass.simpleName} with ID $id was modified concurrently. Please reload it and retry.",
        cause
    )
