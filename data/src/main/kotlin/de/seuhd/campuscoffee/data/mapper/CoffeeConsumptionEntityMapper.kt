package de.seuhd.campuscoffee.data.mapper

import de.seuhd.campuscoffee.data.persistence.entities.CoffeeConsumptionEntity
import de.seuhd.campuscoffee.domain.model.CoffeeConsumption
import org.mapstruct.Mapper
import org.mapstruct.Mapping
import org.mapstruct.MappingTarget
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean

/**
 * MapStruct mapper between [CoffeeConsumption] domain objects and [CoffeeConsumptionEntity] persistence
 * entities. Modeled on CampusCoffee's review entity mapper: the nested [CoffeeConsumption.user] maps
 * through the [UserEntityMapper], and the entity's `version` column (data-layer optimistic locking) has no
 * domain counterpart, so it is left to JPA on insert and preserved on update.
 */
@Mapper(componentModel = "spring", uses = [UserEntityMapper::class])
@ConditionalOnMissingBean // prevent IntelliJ warning about duplicate beans
interface CoffeeConsumptionEntityMapper : EntityMapper<CoffeeConsumption, CoffeeConsumptionEntity> {
    @Mapping(target = "version", ignore = true)
    override fun toEntity(source: CoffeeConsumption): CoffeeConsumptionEntity

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "version", ignore = true)
    override fun updateEntity(
        source: CoffeeConsumption,
        @MappingTarget target: CoffeeConsumptionEntity
    )
}
