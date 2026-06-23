package de.seuhd.campuscoffee.domain.exceptions

/**
 * Thrown when a request is malformed or violates a business rule (a 400): a `delta` other than `±1`, a count
 * correction below zero, an expense whose split does not sum to its total, and the like. Carries a
 * human-readable message; the controller-layer Bean Validation handles field-level constraint reporting
 * before a DTO ever reaches the domain.
 *
 * @param message the validation error message
 */
class ValidationException(
    message: String
) : RuntimeException(message)
