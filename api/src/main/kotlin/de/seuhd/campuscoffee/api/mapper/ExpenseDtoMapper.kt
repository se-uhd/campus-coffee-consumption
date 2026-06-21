package de.seuhd.campuscoffee.api.mapper

import de.seuhd.campuscoffee.api.dtos.ExpenseDto
import de.seuhd.campuscoffee.domain.model.Expense
import org.mapstruct.Mapper
import org.mapstruct.Mapping
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean

/**
 * MapStruct mapper from an [Expense] domain object to its response DTO (one-way), flattening the buyer to
 * their id and login name.
 */
@Mapper(componentModel = "spring")
@ConditionalOnMissingBean // prevent IntelliJ warning about duplicate beans
interface ExpenseDtoMapper {
    /**
     * Maps an expense to its response DTO.
     *
     * @param expense the expense to map
     */
    @Mapping(target = "buyerUserId", source = "buyer.id")
    @Mapping(target = "buyerLoginName", source = "buyer.loginName")
    fun toDto(expense: Expense): ExpenseDto

    /**
     * Maps a list of expenses to their response DTOs.
     *
     * @param expenses the expenses to map
     */
    fun toDtos(expenses: List<Expense>): List<ExpenseDto>
}
