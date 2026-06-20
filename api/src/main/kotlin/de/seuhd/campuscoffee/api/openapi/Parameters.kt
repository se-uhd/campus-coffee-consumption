package de.seuhd.campuscoffee.api.openapi

/**
 * Parameters extracted from a [CrudOperation] annotation, exposing the display forms of the
 * resource names needed to generate the OpenAPI documentation.
 */
class Parameters(
    val operation: Operation,
    val resource: Resource,
    val externalResource: Resource?
) {
    /** The resource name in the form (singular/plural) appropriate for the operation. */
    val resourceName: String
        get() = resource.displayNameForOperation(operation)

    /** The external resource name in the appropriate form, or null if there is no external resource. */
    val externalResourceName: String?
        get() = externalResource?.displayNameForOperation(operation)
}
