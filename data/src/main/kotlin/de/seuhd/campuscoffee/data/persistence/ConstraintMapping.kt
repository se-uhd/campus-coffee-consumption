package de.seuhd.campuscoffee.data.persistence

import org.hibernate.exception.ConstraintViolationException
import org.springframework.dao.DataIntegrityViolationException

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

    companion object {
        /**
         * Returns the name of the database constraint reported by a data integrity violation, or null
         * when the cause chain contains no Hibernate [ConstraintViolationException]. Reading the name
         * the driver reported avoids matching on database-specific error-message text. Exposed (not
         * private) so it can be unit-tested directly with a crafted exception.
         *
         * @param exception the data integrity violation whose cause chain is inspected
         */
        fun constraintNameOf(exception: DataIntegrityViolationException): String? {
            var cause: Throwable? = exception
            while (cause != null) {
                if (cause is ConstraintViolationException) {
                    return cause.constraintName
                }
                cause = cause.cause
            }
            return null
        }
    }
}
