package de.seuhd.campuscoffee.api.mapper

import de.seuhd.campuscoffee.api.dtos.Dto
import de.seuhd.campuscoffee.domain.model.DomainModel

/**
 * Generic mapper interface for converting between domain models and DTOs (bidirectional).
 *
 * Part of the API-layer adapter in the hexagonal architecture, keeping the domain layer
 * independent of API concerns.
 *
 * @param DOMAIN the domain model type
 * @param DTO    the data transfer object type
 */
interface DtoMapper<DOMAIN : DomainModel<*>, DTO : Dto<*>> {
    /**
     * Converts a domain model object to its DTO representation.
     *
     * @param source the domain model object to convert
     */
    fun fromDomain(source: DOMAIN): DTO

    /**
     * Converts a DTO to its domain model representation.
     *
     * @param source the DTO to convert
     */
    fun toDomain(source: DTO): DOMAIN
}
