package de.seuhd.campuscoffee.api.dtos

/**
 * Response DTO for a user's landing page in one read: the coffee [count], the current price per cup
 * ([priceCents]), the user's [balanceCents] (negative ⇒ they owe the fund), the communal
 * [kittyBalanceCents] (read-only for users), whether their most recent coffee is still [cancellable]
 * within the grace period, and the first page of their unified [activity] (newest first). Money is euro cents.
 */
data class UserSummaryDto(
    val count: Int,
    val priceCents: Int,
    val balanceCents: Long,
    val kittyBalanceCents: Long,
    val cancellable: Boolean,
    val activity: List<ActivityEntryDto>
)
