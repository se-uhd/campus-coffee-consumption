package de.seuhd.campuscoffee.domain.model.objects

/**
 * Base type for all domain model objects, identifiable so they expose their identifier.
 */
interface DomainModel<ID> : Identifiable<ID>
