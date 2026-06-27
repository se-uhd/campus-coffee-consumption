package de.seuhd.campuscoffee.api.dtos

/**
 * Response DTO for the communal kitty: the current [balanceCents] (euro cents) and, for the admin kitty
 * history, a page of its individual movements ([entries], newest first). The user-facing kitty balance
 * arrives in the [UserSummaryDto], so a user never receives the detailed entries.
 */
data class KittyDto(
    val balanceCents: Long,
    val entries: List<ActivityEntryDto>
)
