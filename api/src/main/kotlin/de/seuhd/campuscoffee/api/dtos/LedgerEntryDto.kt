package de.seuhd.campuscoffee.api.dtos

import com.fasterxml.jackson.annotation.JsonInclude
import de.seuhd.campuscoffee.domain.model.LedgerEntryType
import java.time.LocalDateTime
import java.util.UUID

/**
 * Response DTO for one row of a unified ledger (a member's or the kitty's). [id] is a stable per-entry key
 * (the underlying event's id) for client-side paging and deduplication. [type] discriminates the row;
 * [amountCents] is its signed effect on the balance and [runningBalanceCents] the balance after it (euro
 * cents). [count]/[delta] are present only for a consumption row and [weightGrams] only for an expense row,
 * so they are omitted when absent. [privateAmountCents]/[kittyAmountCents] are present together only on the
 * **admin** views of a split bean purchase (its member-funded and kitty-funded portions): both the member
 * ledger's PRIVATE_EXPENSE row and the kitty ledger's KITTY_EXPENSE row carry them. The member-serving read
 * strips both, so a member never sees the split (their purchases read as 100% private).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class LedgerEntryDto(
    val type: LedgerEntryType,
    val id: UUID,
    val createdAt: LocalDateTime,
    val createdBy: String,
    val note: String? = null,
    val amountCents: Long,
    val runningBalanceCents: Long,
    val count: Int? = null,
    val delta: Int? = null,
    val weightGrams: Int? = null,
    val privateAmountCents: Long? = null,
    val kittyAmountCents: Long? = null
)
