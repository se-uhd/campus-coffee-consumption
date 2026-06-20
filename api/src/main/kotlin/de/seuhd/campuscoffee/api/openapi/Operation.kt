package de.seuhd.campuscoffee.api.openapi

import org.springframework.http.HttpStatus

/**
 * Supported CRUD operation types, each with its OpenAPI summary template and the response
 * specifications (status codes, description templates, and which are error responses).
 *
 * @property summaryTemplate builds the operation summary from the resolved [Parameters]
 * @property responseSpecifications the response specs (status codes, description templates, error flags)
 */
enum class Operation(
    val summaryTemplate: (Parameters) -> String,
    val responseSpecifications: List<CrudResponseSpecification>
) {
    GET_ALL(
        { params -> "Get all ${params.resourceName}." },
        listOf(
            CrudResponseSpecification(HttpStatus.OK, "All %s as a JSON array.")
        )
    ),
    GET_BY_ID(
        { params -> "Get ${params.resourceName} by ID." },
        listOf(
            CrudResponseSpecification(HttpStatus.OK, "The %s with the provided ID as a JSON object."),
            CrudResponseSpecification(
                HttpStatus.NOT_FOUND,
                "No %s with the provided ID could be found.",
                isErrorResponse = true
            )
        )
    ),
    CREATE(
        { params -> "Create a new ${params.resourceName}." },
        listOf(
            CrudResponseSpecification(HttpStatus.CREATED, "The new %s as a JSON object."),
            CrudResponseSpecification(
                HttpStatus.BAD_REQUEST,
                "Validation failed (e.g., bean validation error, or the request body carries an ID — " +
                    "IDs are assigned by the server).",
                isErrorResponse = true
            ),
            CrudResponseSpecification(
                HttpStatus.UNAUTHORIZED,
                "Authentication is required.",
                isErrorResponse = true
            ),
            CrudResponseSpecification(
                HttpStatus.CONFLICT,
                "A %s with the same value for a unique field already exists.",
                isErrorResponse = true
            )
        )
    ),
    UPDATE(
        { params -> "Update ${params.resourceName} by ID." },
        listOf(
            CrudResponseSpecification(HttpStatus.OK, "The updated %s as a JSON object."),
            CrudResponseSpecification(
                HttpStatus.BAD_REQUEST,
                "Validation failed (e.g., IDs in path and body do not match, bean validation error).",
                isErrorResponse = true
            ),
            CrudResponseSpecification(
                HttpStatus.UNAUTHORIZED,
                "Authentication is required.",
                isErrorResponse = true
            ),
            CrudResponseSpecification(
                HttpStatus.FORBIDDEN,
                "The authenticated user may not update this %s (e.g., not the owner, or not an admin).",
                isErrorResponse = true
            ),
            CrudResponseSpecification(
                HttpStatus.NOT_FOUND,
                "No %s with the provided ID could be found.",
                isErrorResponse = true
            ),
            CrudResponseSpecification(
                HttpStatus.CONFLICT,
                "A %s with the same unique identifier already exists.",
                isErrorResponse = true
            )
        )
    ),
    DELETE(
        { params -> "Delete ${params.resourceName} by ID." },
        listOf(
            CrudResponseSpecification(HttpStatus.NO_CONTENT, "The %s was successfully deleted."),
            CrudResponseSpecification(
                HttpStatus.UNAUTHORIZED,
                "Authentication is required.",
                isErrorResponse = true
            ),
            CrudResponseSpecification(
                HttpStatus.FORBIDDEN,
                "The authenticated user may not delete this %s (e.g., not an admin).",
                isErrorResponse = true
            ),
            CrudResponseSpecification(
                HttpStatus.NOT_FOUND,
                "No %s with the provided ID could be found.",
                isErrorResponse = true
            ),
            CrudResponseSpecification(
                HttpStatus.CONFLICT,
                "The %s cannot be deleted because other data still references it.",
                isErrorResponse = true
            )
        )
    ),
    FILTER(
        { params -> "Filter ${params.resourceName} by a selected field." },
        listOf(
            CrudResponseSpecification(HttpStatus.OK, "The %s matching the filter criteria as a JSON object."),
            CrudResponseSpecification(
                HttpStatus.NOT_FOUND,
                "No %s matching the filter criteria could be found.",
                isErrorResponse = true
            )
        )
    )
}
