package de.seuhd.campuscoffee.domain.exceptions

import jakarta.validation.ConstraintViolation

/**
 * Base exception thrown when an entity fails Bean Validation or violates a business rule.
 */
class ValidationException : RuntimeException {
    val violations: Set<ConstraintViolation<*>>

    @Suppress("unused") // will later be used when manually validating objects
    constructor(violations: Set<ConstraintViolation<*>>) : super(formatViolations(violations)) {
        this.violations = violations
    }

    /**
     * Creates a validation exception with a custom message for business rule violations.
     *
     * @param message the validation error message
     */
    constructor(message: String) : super(message) {
        this.violations = emptySet()
    }

    private companion object {
        /**
         * Formats constraint violations into a readable message.
         *
         * @param violations the constraint violations to format
         */
        fun formatViolations(violations: Set<ConstraintViolation<*>>): String =
            violations.joinToString(", ") { "${it.propertyPath}: ${it.message}" }
    }
}
