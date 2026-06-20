package de.seuhd.campuscoffee.domain.exceptions

/**
 * Thrown when a well-formed request conflicts with the current state of a resource — for example,
 * decrementing a coffee count that is already zero. Distinct from [ValidationException] (a malformed input,
 * such as a delta that is not +1/-1): here the request is valid in itself but cannot apply to the
 * resource's current value. Maps to 409 Conflict.
 */
class ConflictException(
    message: String
) : RuntimeException(message)
