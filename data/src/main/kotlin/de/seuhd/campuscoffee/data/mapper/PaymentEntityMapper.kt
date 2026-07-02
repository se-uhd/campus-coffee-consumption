package de.seuhd.campuscoffee.data.mapper

import de.seuhd.campuscoffee.data.persistence.entities.PaymentEntity
import de.seuhd.campuscoffee.domain.model.Payment
import org.mapstruct.Mapper
import org.mapstruct.Mapping
import org.mapstruct.MappingTarget
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean

/**
 * MapStruct mapper between [Payment] domain objects and [PaymentEntity] persistence entities. On the write
 * side ([toEntity]/[updateEntity]) the nullable [Payment.user] association is set as a managed reference by id
 * via the [EntityReferenceResolver] (null for a pure kitty adjustment), so a write only sets the foreign key
 * and never rewrites the referenced user row; on the read side [fromEntity] maps it deeply through the
 * [UserEntityMapper]. The entity's `version` column (data-layer optimistic locking) has no domain
 * counterpart, so it is left to JPA on insert and preserved on update.
 */
@Mapper(componentModel = "spring", uses = [UserEntityMapper::class, EntityReferenceResolver::class])
@ConditionalOnMissingBean // prevent IntelliJ warning about duplicate beans
interface PaymentEntityMapper : EntityMapper<Payment, PaymentEntity> {
    @Mapping(target = "user", source = "user.id")
    @Mapping(target = "version", ignore = true)
    override fun toEntity(source: Payment): PaymentEntity

    @Mapping(target = "user", source = "user.id")
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "version", ignore = true)
    override fun updateEntity(
        source: Payment,
        @MappingTarget target: PaymentEntity
    )
}
