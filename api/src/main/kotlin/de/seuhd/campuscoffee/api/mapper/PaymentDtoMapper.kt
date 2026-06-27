package de.seuhd.campuscoffee.api.mapper

import de.seuhd.campuscoffee.api.dtos.PaymentDto
import de.seuhd.campuscoffee.domain.model.Payment
import org.mapstruct.Mapper
import org.mapstruct.Mapping
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean

/**
 * MapStruct mapper from a [Payment] domain object to its response DTO (one-way), flattening the user (if
 * any) to their id and login name; both are null for a pure kitty adjustment.
 */
@Mapper(componentModel = "spring")
@ConditionalOnMissingBean // prevent IntelliJ warning about duplicate beans
interface PaymentDtoMapper {
    /**
     * Maps a payment to its response DTO.
     *
     * @param payment the payment to map
     */
    @Mapping(target = "userId", source = "user.id")
    @Mapping(target = "userLoginName", source = "user.loginName")
    fun toDto(payment: Payment): PaymentDto
}
