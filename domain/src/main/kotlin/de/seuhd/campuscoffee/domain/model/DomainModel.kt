package de.seuhd.campuscoffee.domain.model

/**
 * Base type for all domain model objects, identifiable so they expose their identifier.
 */
interface DomainModel<ID> : Identifiable<ID>
