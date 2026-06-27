package de.seuhd.campuscoffee.domain.model

/**
 * Domain objects and DTOs that carry an identifier. Enables generic CRUD operations in the base
 * controllers and services by exposing the ID in a uniform way.
 */
interface Identifiable<T> {
    /** The unique identifier, or null if the resource has not been created yet. */
    val id: T?
}

/**
 * This object's non-null [id]. Use it only when the object is known to be saved; on an unsaved object (whose
 * [id] is still null) it throws a `NullPointerException`.
 */
val <ID : Any> Identifiable<ID>.persistedId: ID get() = id!!
