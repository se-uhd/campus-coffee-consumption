package de.seuhd.campuscoffee.api.dtos

/**
 * Response DTO for a member's landing page in one read: the coffee [count], the current price per cup
 * ([priceCents]), the member's [balanceCents] (negative ⇒ they owe the fund), the communal
 * [kittyBalanceCents] (read-only for members), whether their most recent coffee is still [cancellable]
 * within the grace period, and the first page of their unified [ledger] (newest first). Money is euro cents.
 */
data class MemberSummaryDto(
    val count: Int,
    val priceCents: Int,
    val balanceCents: Long,
    val kittyBalanceCents: Long,
    val cancellable: Boolean,
    val ledger: List<LedgerEntryDto>
)
