package de.seuhd.campuscoffee.domain.exceptions

/**
 * Generic exception thrown when attempting to create or update an entity with a value that already exists.
 * This represents a business rule violation: certain fields must be unique.
 *
 * @param domainClass class of domain object (e.g., "User", "CoffeeConsumption")
 * @param fieldName   the field name that must be unique (e.g., "name", "login name", "email address")
 * @param fieldValue  the duplicate value
 */
class DuplicationException(
    domainClass: Class<*>,
    fieldName: String,
    fieldValue: String
) : RuntimeException("${domainClass.simpleName} with $fieldName '$fieldValue' already exists.")
