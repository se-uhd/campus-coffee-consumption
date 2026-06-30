package de.seuhd.campuscoffee.domain.model

import java.time.LocalDateTime

/**
 * The data a user's landing page needs in one read: the current coffee [count], the current price per
 * cup ([priceCents]), the user's [balanceCents] (negative ⇒ they owe the fund), the communal
 * [kittyBalanceCents] (read-only for users), whether their most recent coffee is still [cancellable]
 * within the grace period, the user's [summaryPanel] preference (which second card their landing shows), and
 * the first page of their unified [activity] (newest first). All money is in euro cents.
 *
 * The cup-stat fields back the [SummaryPanel.CUPS] panel and are always computed (the landing chooses which
 * card to render): [firstCupAt] is the time of the user's first coffee (null if they have none), and
 * [cupsThisWeek] / [cupsToday] are the net cups since the start of the local week / day. "Cups since the
 * first" is the existing [count].
 */
data class UserSummary(
    val count: Int,
    val priceCents: Int,
    val balanceCents: Long,
    val kittyBalanceCents: Long,
    val cancellable: Boolean,
    val summaryPanel: SummaryPanel,
    val firstCupAt: LocalDateTime?,
    val cupsThisWeek: Int,
    val cupsToday: Int,
    val activity: List<ActivityEntry>
)
