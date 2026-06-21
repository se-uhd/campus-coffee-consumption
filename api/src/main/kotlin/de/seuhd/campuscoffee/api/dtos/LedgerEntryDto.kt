package de.seuhd.campuscoffee.api.dtos

import com.fasterxml.jackson.annotation.JsonInclude
import de.seuhd.campuscoffee.domain.model.LedgerEntryType
import java.time.LocalDateTime

/**
 * Response DTO for one row of a unified ledger (a member's or the kitty's). [type] discriminates the row;
 * [amountCents] is its signed effect on the balance and [runningBalanceCents] the balance after it (euro
 * cents). [count]/[delta] are present only for a consumption row and [weightGrams] only for an expense row,
 * so they are omitted when absent.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class LedgerEntryDto(
    val type: LedgerEntryType,
    val seq: Long,
    val createdAt: LocalDateTime,
    val createdBy: String,
    val note: String? = null,
    val amountCents: Long,
    val runningBalanceCents: Long,
    val count: Int? = null,
    val delta: Int? = null,
    val weightGrams: Int? = null
)
