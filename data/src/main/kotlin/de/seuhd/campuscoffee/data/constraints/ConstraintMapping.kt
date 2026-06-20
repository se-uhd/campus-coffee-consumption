package de.seuhd.campuscoffee.data.constraints

/**
 * Maps a database unique constraint to the domain field it guards. When the constraint is violated, the
 * value extractor reads the offending value from the domain object for the DuplicationException message.
 *
 * @param DOMAIN         the domain model type that holds the unique field
 * @param valueExtractor reads the unique field's value from the domain object (e.g., `{ it.name }`)
 * @param columnName     the database column of the unique field
 * @param constraintName the name of the unique constraint in the database
 */
class ConstraintMapping<DOMAIN>(
    private val valueExtractor: (DOMAIN) -> Any?,
    val columnName: String,
    val constraintName: String
) {
    /**
     * Reads the guarded field's value from the domain object for the DuplicationException message.
     *
     * @param domain the domain object that violated the constraint
     * @return the offending field value
     */
    fun extractValue(domain: DOMAIN): Any? = valueExtractor(domain)
}
