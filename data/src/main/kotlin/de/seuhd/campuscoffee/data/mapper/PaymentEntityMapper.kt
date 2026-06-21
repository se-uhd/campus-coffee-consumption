package de.seuhd.campuscoffee.data.mapper

import de.seuhd.campuscoffee.data.persistence.entities.PaymentEntity
import de.seuhd.campuscoffee.domain.model.Payment
import org.mapstruct.Mapper
import org.mapstruct.Mapping
import org.mapstruct.MappingTarget
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean

/**
 * MapStruct mapper between [Payment] domain objects and [PaymentEntity] persistence entities. The nullable
 * nested [Payment.user] maps through the [UserEntityMapper] (null for a pure kitty adjustment), and the
 * entity's `version` column (data-layer optimistic locking) has no domain counterpart, so it is left to JPA
 * on insert and preserved on update.
 */
@Mapper(componentModel = "spring", uses = [UserEntityMapper::class])
@ConditionalOnMissingBean // prevent IntelliJ warning about duplicate beans
interface PaymentEntityMapper : EntityMapper<Payment, PaymentEntity> {
    @Mapping(target = "version", ignore = true)
    override fun toEntity(source: Payment): PaymentEntity

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "version", ignore = true)
    override fun updateEntity(
        source: Payment,
        @MappingTarget target: PaymentEntity
    )
}
