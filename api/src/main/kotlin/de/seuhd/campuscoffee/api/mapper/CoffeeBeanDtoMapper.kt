package de.seuhd.campuscoffee.api.mapper

import de.seuhd.campuscoffee.api.dtos.CoffeeBeanDto
import de.seuhd.campuscoffee.api.dtos.CoffeeBeanRatingsDto
import de.seuhd.campuscoffee.domain.model.CoffeeBean
import de.seuhd.campuscoffee.domain.model.CoffeeBeanRatings
import org.mapstruct.Mapper
import org.mapstruct.Mapping
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean

/**
 * MapStruct mapper from [CoffeeBean] and [CoffeeBeanRatings] domain objects to their response DTOs (one-way).
 */
@Mapper(componentModel = "spring")
@ConditionalOnMissingBean // prevent IntelliJ warning about duplicate beans
interface CoffeeBeanDtoMapper {
    /**
     * Maps a bean to its response DTO.
     *
     * @param bean the bean to map
     */
    fun toDto(bean: CoffeeBean): CoffeeBeanDto

    /**
     * Maps a list of beans to their response DTOs.
     *
     * @param beans the beans to map
     */
    fun toDtos(beans: List<CoffeeBean>): List<CoffeeBeanDto>

    /**
     * Maps a rating row to its response DTO, flattening the bean to its id and name.
     *
     * @param rating the rating row to map
     */
    @Mapping(target = "beanId", source = "bean.id")
    @Mapping(target = "name", source = "bean.name")
    fun toRatingsDto(rating: CoffeeBeanRatings): CoffeeBeanRatingsDto

    /**
     * Maps a list of rating rows to their response DTOs.
     *
     * @param ratings the rating rows to map
     */
    fun toRatingsDtos(ratings: List<CoffeeBeanRatings>): List<CoffeeBeanRatingsDto>
}
