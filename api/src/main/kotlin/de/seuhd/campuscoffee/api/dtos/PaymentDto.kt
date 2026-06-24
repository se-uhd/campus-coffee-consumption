package de.seuhd.campuscoffee.api.dtos

import com.fasterxml.jackson.annotation.JsonInclude
import java.time.LocalDateTime
import java.util.UUID

/**
 * Response DTO for a kitty money movement: a deposit (with a member) or a kitty adjustment (without).
 * The member, when present, is flattened to their id and login name. [amountCents] is in euro cents
 * (positive for a deposit; signed for an adjustment).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class PaymentDto(
    val id: UUID,
    val userId: UUID? = null,
    val userLoginName: String? = null,
    val amountCents: Int,
    val note: String? = null,
    val createdAt: LocalDateTime? = null
)
