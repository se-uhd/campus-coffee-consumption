package de.seuhd.campuscoffee.api.openapi

import org.springframework.http.HttpStatus

/**
 * Specification of a single API response.
 *
 * @param httpStatus          the HTTP status (e.g., 200, 404, 409)
 * @param descriptionTemplate description template with a `%s` placeholder for the resource name
 * @param isErrorResponse     whether this is an error response (so the ErrorResponse schema is returned)
 * @param isExternalResource  whether to substitute the external resource name into the description
 */
class CrudResponseSpecification(
    val httpStatus: HttpStatus,
    val descriptionTemplate: String,
    val isErrorResponse: Boolean = false,
    val isExternalResource: Boolean = false
)
