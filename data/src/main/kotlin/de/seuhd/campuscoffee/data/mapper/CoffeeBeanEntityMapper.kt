package de.seuhd.campuscoffee.data.mapper

import de.seuhd.campuscoffee.data.persistence.entities.CoffeeBeanEntity
import de.seuhd.campuscoffee.domain.model.CoffeeBean
import org.mapstruct.Mapper
import org.mapstruct.Mapping
import org.mapstruct.MappingTarget
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean

/**
 * MapStruct mapper between [CoffeeBean] domain objects and [CoffeeBeanEntity] persistence entities. The
 * entity's `version` column (data-layer optimistic locking) has no domain counterpart, so it is left to JPA
 * on insert and preserved on update. The bean has no nested associations (`mergedIntoId` is a plain id).
 */
@Mapper(componentModel = "spring")
@ConditionalOnMissingBean // prevent IntelliJ warning about duplicate beans
interface CoffeeBeanEntityMapper : EntityMapper<CoffeeBean, CoffeeBeanEntity> {
    @Mapping(target = "version", ignore = true)
    override fun toEntity(source: CoffeeBean): CoffeeBeanEntity

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "version", ignore = true)
    override fun updateEntity(
        source: CoffeeBean,
        @MappingTarget target: CoffeeBeanEntity
    )
}
