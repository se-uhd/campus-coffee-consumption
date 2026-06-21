package de.seuhd.campuscoffee.data.mapper

import de.seuhd.campuscoffee.data.persistence.entities.CoffeePriceEntity
import de.seuhd.campuscoffee.domain.model.CoffeePrice
import org.mapstruct.Mapper
import org.mapstruct.Mapping
import org.mapstruct.MappingTarget
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean

/**
 * MapStruct mapper between [CoffeePrice] domain objects and [CoffeePriceEntity] persistence entities. The
 * entity's `version` column (data-layer optimistic locking) has no domain counterpart, so it is left to JPA
 * on insert and preserved on update.
 */
@Mapper(componentModel = "spring")
@ConditionalOnMissingBean // prevent IntelliJ warning about duplicate beans
interface CoffeePriceEntityMapper : EntityMapper<CoffeePrice, CoffeePriceEntity> {
    @Mapping(target = "version", ignore = true)
    override fun toEntity(source: CoffeePrice): CoffeePriceEntity

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "version", ignore = true)
    override fun updateEntity(
        source: CoffeePrice,
        @MappingTarget target: CoffeePriceEntity
    )
}
