package de.seuhd.campuscoffee.api.dtos

/**
 * Response DTO for the communal kitty: the current [balanceCents] (euro cents) and, for the admin kitty
 * ledger, a page of its individual movements ([entries], newest first). The member-facing kitty balance
 * arrives in the [MemberSummaryDto], so a member never receives the detailed entries.
 */
data class KittyDto(
    val balanceCents: Long,
    val entries: List<LedgerEntryDto>
)
