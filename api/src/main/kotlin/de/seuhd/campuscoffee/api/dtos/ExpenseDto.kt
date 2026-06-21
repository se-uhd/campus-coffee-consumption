package de.seuhd.campuscoffee.api.dtos

import com.fasterxml.jackson.annotation.JsonInclude
import java.time.LocalDateTime
import java.util.UUID

/**
 * Response DTO for a recorded bean purchase. The buyer is flattened to their id and login name. All money
 * is in euro cents; [privateAmountCents] and [kittyAmountCents] always sum to [amountCents].
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ExpenseDto(
    val id: UUID,
    val buyerUserId: UUID,
    val buyerLoginName: String,
    val weightGrams: Int,
    val amountCents: Int,
    val privateAmountCents: Int,
    val kittyAmountCents: Int,
    val note: String? = null,
    val createdAt: LocalDateTime? = null
)
