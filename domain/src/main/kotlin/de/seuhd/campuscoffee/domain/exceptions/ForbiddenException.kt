package de.seuhd.campuscoffee.domain.exceptions

/**
 * Thrown when an authenticated user is not allowed to perform an action on a specific resource, such as
 * changing another user's coffee count or editing an account they do not own. This is an *authorization*
 * failure (the caller is known but lacks permission), so it maps to 403 Forbidden, distinct from the 401
 * raised when no authenticated user is present at all.
 *
 * The decision is made in the domain by comparing the acting [User][de.seuhd.campuscoffee.domain.model.User]
 * (and its roles) with the resource, so the domain never depends on Spring Security types.
 */
class ForbiddenException(
    message: String
) : RuntimeException(message)
