package de.seuhd.campuscoffee.data.mapper

import de.seuhd.campuscoffee.data.persistence.entities.Entity
import de.seuhd.campuscoffee.domain.model.DomainModel
import org.mapstruct.MappingTarget

/**
 * Generic mapper interface for converting between domain models and JPA entities (bidirectional).
 * Part of the data-layer adapter in the hexagonal architecture.
 *
 * @param DOMAIN the domain model type
 * @param ENTITY the JPA entity type
 */
interface EntityMapper<DOMAIN : DomainModel<*>, ENTITY : Entity> {
    /**
     * Converts a JPA entity to its domain model representation.
     *
     * @param source the JPA entity to convert
     */
    fun fromEntity(source: ENTITY): DOMAIN

    /**
     * Converts a domain model object to its JPA entity representation.
     *
     * @param source the domain model to convert
     */
    fun toEntity(source: DOMAIN): ENTITY

    /**
     * Updates an existing JPA entity with data from the domain model. JPA-managed fields (id,
     * createdAt, updatedAt) are preserved.
     *
     * @param source the domain model holding the new values
     * @param target the JPA entity to update in place
     */
    fun updateEntity(
        source: DOMAIN,
        @MappingTarget target: ENTITY
    )
}
