package de.seuhd.campuscoffee.domain.exceptions

/**
 * Generic exception thrown when an entity is missing a required field.
 * This represents a business rule violation: certain fields are mandatory.
 *
 * @param domainClass class of domain object (e.g., "User", "CoffeeConsumption")
 * @param id          unique identifier of the domain object with a missing field
 * @param fieldName   name of the missing field
 */
class MissingFieldException(
    domainClass: Class<*>,
    id: Any?,
    fieldName: String
) : RuntimeException(
        "${domainClass.simpleName} with ID $id does not have the required fields. " +
            "Field '$fieldName' is missing."
    )
