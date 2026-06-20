package de.seuhd.campuscoffee.api.exceptions

import com.fasterxml.jackson.annotation.JsonInclude
import java.time.LocalDateTime

/**
 * Standardized error response for all API exceptions.
 *
 * @param errorCode     machine-readable code based on the exception class name (e.g., NotFoundException)
 * @param message       human-readable error message; null is allowed and omitted from the JSON
 * @param statusCode    HTTP status code (e.g., 400, 404, 500)
 * @param statusMessage HTTP status message (e.g., "Bad Request", "Not Found")
 * @param timestamp     when the error occurred
 * @param path          request path that caused the error
 */
@JsonInclude(JsonInclude.Include.NON_NULL) // excludes null fields from JSON
data class ErrorResponse(
    val errorCode: String,
    val message: String?,
    val statusCode: Int,
    val statusMessage: String? = null,
    val timestamp: LocalDateTime? = null,
    val path: String? = null
)
