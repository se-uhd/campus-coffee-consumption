package de.seuhd.campuscoffee.api.dtos

import de.seuhd.campuscoffee.domain.model.objects.Identifiable

/**
 * Marker interface for Data Transfer Objects (DTOs) that have an identifier. Extends [Identifiable]
 * so every DTO exposes its unique identifier.
 */
interface Dto<ID> : Identifiable<ID>
