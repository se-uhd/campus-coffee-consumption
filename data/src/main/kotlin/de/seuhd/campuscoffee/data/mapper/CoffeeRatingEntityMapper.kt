package de.seuhd.campuscoffee.data.mapper

import de.seuhd.campuscoffee.data.persistence.entities.CoffeeRatingEntity
import de.seuhd.campuscoffee.domain.model.CoffeeRating
import org.mapstruct.Mapper
import org.mapstruct.Mapping
import org.mapstruct.MappingTarget
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean

/**
 * MapStruct mapper between [CoffeeRating] domain objects and [CoffeeRatingEntity] persistence entities. On
 * the write side ([toEntity]/[updateEntity]) the [CoffeeRating.user] and [CoffeeRating.bean] associations are
 * set as managed references by id via the [EntityReferenceResolver], so a write only sets the foreign key and
 * never rewrites the referenced user or bean row (a plain nested map would rename the previously referenced
 * bean when a vote switches beans). On the read side [fromEntity] maps them deeply through the
 * [UserEntityMapper] and [CoffeeBeanEntityMapper]. The entity's `version` column has no domain counterpart.
 */
@Mapper(
    componentModel = "spring",
    uses = [UserEntityMapper::class, CoffeeBeanEntityMapper::class, EntityReferenceResolver::class]
)
@ConditionalOnMissingBean // prevent IntelliJ warning about duplicate beans
interface CoffeeRatingEntityMapper : EntityMapper<CoffeeRating, CoffeeRatingEntity> {
    @Mapping(target = "user", source = "user.id")
    @Mapping(target = "bean", source = "bean.id")
    @Mapping(target = "version", ignore = true)
    override fun toEntity(source: CoffeeRating): CoffeeRatingEntity

    @Mapping(target = "user", source = "user.id")
    @Mapping(target = "bean", source = "bean.id")
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "version", ignore = true)
    override fun updateEntity(
        source: CoffeeRating,
        @MappingTarget target: CoffeeRatingEntity
    )
}
