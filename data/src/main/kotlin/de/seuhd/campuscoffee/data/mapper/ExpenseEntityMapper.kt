package de.seuhd.campuscoffee.data.mapper

import de.seuhd.campuscoffee.data.persistence.entities.ExpenseEntity
import de.seuhd.campuscoffee.domain.model.Expense
import org.mapstruct.Mapper
import org.mapstruct.Mapping
import org.mapstruct.MappingTarget
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean

/**
 * MapStruct mapper between [Expense] domain objects and [ExpenseEntity] persistence entities. The nested
 * [Expense.buyer] maps through the [UserEntityMapper], and the entity's `version` column (data-layer
 * optimistic locking) has no domain counterpart, so it is left to JPA on insert and preserved on update.
 */
@Mapper(componentModel = "spring", uses = [UserEntityMapper::class])
@ConditionalOnMissingBean // prevent IntelliJ warning about duplicate beans
interface ExpenseEntityMapper : EntityMapper<Expense, ExpenseEntity> {
    @Mapping(target = "version", ignore = true)
    override fun toEntity(source: Expense): ExpenseEntity

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "version", ignore = true)
    override fun updateEntity(
        source: Expense,
        @MappingTarget target: ExpenseEntity
    )
}
