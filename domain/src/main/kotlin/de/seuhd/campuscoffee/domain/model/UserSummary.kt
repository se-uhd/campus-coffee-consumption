package de.seuhd.campuscoffee.domain.model

/**
 * The data a user's landing page needs in one read: the current coffee [count], the current price per
 * cup ([priceCents]), the user's [balanceCents] (negative ⇒ they owe the fund), the communal
 * [kittyBalanceCents] (read-only for users), whether their most recent coffee is still [cancellable]
 * within the grace period, and the first page of their unified [activity] (newest first). All money is in
 * euro cents.
 */
data class UserSummary(
    val count: Int,
    val priceCents: Int,
    val balanceCents: Long,
    val kittyBalanceCents: Long,
    val cancellable: Boolean,
    val activity: List<ActivityEntry>
)
