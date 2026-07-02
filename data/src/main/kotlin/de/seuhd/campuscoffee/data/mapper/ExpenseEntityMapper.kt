package de.seuhd.campuscoffee.data.mapper

import de.seuhd.campuscoffee.data.persistence.entities.ExpenseEntity
import de.seuhd.campuscoffee.domain.model.Expense
import org.mapstruct.Mapper
import org.mapstruct.Mapping
import org.mapstruct.MappingTarget
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean

/**
 * MapStruct mapper between [Expense] domain objects and [ExpenseEntity] persistence entities. On the write
 * side ([toEntity]/[updateEntity]) the [Expense.buyer] and (nullable) [Expense.bean] associations are set as
 * managed references by id via the [EntityReferenceResolver], so a write only sets the foreign key and never
 * rewrites the referenced user or bean row (a plain nested map would rename the previously referenced bean
 * when a correction switches beans). On the read side [fromEntity] maps them deeply through the
 * [UserEntityMapper] and [CoffeeBeanEntityMapper]. The entity's `version` column (data-layer optimistic
 * locking) has no domain counterpart, so it is left to JPA on insert and preserved on update.
 */
@Mapper(
    componentModel = "spring",
    uses = [UserEntityMapper::class, CoffeeBeanEntityMapper::class, EntityReferenceResolver::class]
)
@ConditionalOnMissingBean // prevent IntelliJ warning about duplicate beans
interface ExpenseEntityMapper : EntityMapper<Expense, ExpenseEntity> {
    @Mapping(target = "buyer", source = "buyer.id")
    @Mapping(target = "bean", source = "bean.id")
    @Mapping(target = "version", ignore = true)
    override fun toEntity(source: Expense): ExpenseEntity

    @Mapping(target = "buyer", source = "buyer.id")
    @Mapping(target = "bean", source = "bean.id")
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "version", ignore = true)
    override fun updateEntity(
        source: Expense,
        @MappingTarget target: ExpenseEntity
    )
}
