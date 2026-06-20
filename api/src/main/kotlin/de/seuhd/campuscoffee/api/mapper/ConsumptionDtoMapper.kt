package de.seuhd.campuscoffee.api.mapper

import de.seuhd.campuscoffee.api.dtos.ConsumptionChangeDto
import de.seuhd.campuscoffee.api.dtos.ConsumptionDto
import de.seuhd.campuscoffee.domain.model.ConsumptionChange
import org.mapstruct.Mapper
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean

/**
 * MapStruct mapper assembling the consumption response DTOs from the domain. The mapping is one-way
 * (domain to DTO): a consumption mutation request body is just a delta or a total, with no DTO to map
 * back into the domain. The composite [ConsumptionDto] carries the current [total][ConsumptionDto.total]
 * alongside a page of the change log.
 */
@Mapper(componentModel = "spring")
@ConditionalOnMissingBean // prevent IntelliJ warning about duplicate beans
interface ConsumptionDtoMapper {
    /**
     * Maps a single change-log entry to its response DTO.
     *
     * @param change the change to map
     */
    fun toChangeDto(change: ConsumptionChange): ConsumptionChangeDto

    /**
     * Assembles the composite consumption response from the current total and a page of changes.
     *
     * @param total   the current coffee total
     * @param changes the page of changes (newest first) to include
     */
    fun toDto(
        total: Int,
        changes: List<ConsumptionChange>
    ): ConsumptionDto = ConsumptionDto(total, changes.map { toChangeDto(it) })
}
