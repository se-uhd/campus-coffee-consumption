package de.seuhd.campuscoffee.api.dtos

import com.fasterxml.jackson.annotation.JsonInclude
import de.seuhd.campuscoffee.domain.model.ExpenseType
import java.time.LocalDateTime
import java.util.UUID

/**
 * Response DTO for a recorded outlay. The buyer is flattened to their id and login name, and the bean (for a
 * `BEANS` outlay) to its id and name ([beanId]/[beanName] are null for an `OTHER` outlay or a legacy expense
 * with no derived bean yet). All money is in euro cents; [privateAmountCents] and [kittyAmountCents] always
 * sum to [amountCents].
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ExpenseDto(
    val id: UUID,
    val buyerUserId: UUID,
    val buyerLoginName: String,
    val expenseType: ExpenseType,
    val beanId: UUID? = null,
    val beanName: String? = null,
    val weightGrams: Int? = null,
    val amountCents: Int,
    val privateAmountCents: Int,
    val kittyAmountCents: Int,
    val note: String? = null,
    val createdAt: LocalDateTime? = null
)
