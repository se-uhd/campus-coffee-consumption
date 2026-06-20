package de.seuhd.campuscoffee.domain.model

/**
 * Domain objects and DTOs that carry an identifier. Enables generic CRUD operations in the base
 * controllers and services by exposing the ID in a uniform way.
 */
interface Identifiable<T> {
    /** The unique identifier, or null if the resource has not been created yet. */
    val id: T?
}

/** The identifier of a resource that must already be persisted; fails if it has not been created yet. */
val <ID : Any> Identifiable<ID>.persistedId: ID get() = id!!
