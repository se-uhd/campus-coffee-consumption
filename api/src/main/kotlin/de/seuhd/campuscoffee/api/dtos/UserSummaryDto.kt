package de.seuhd.campuscoffee.api.dtos

import de.seuhd.campuscoffee.domain.model.SummaryPanel
import java.time.LocalDateTime

/**
 * Response DTO for a user's landing page in one read: the coffee [count], the current price per cup
 * ([priceCents]), the user's [balanceCents] (negative ⇒ they owe the fund), the communal
 * [kittyBalanceCents] (read-only for users), whether their most recent coffee is still [cancellable]
 * within the grace period, the [summaryPanel] preference (which second card their landing shows), and the
 * first page of their unified [activity] (newest first). Money is euro cents.
 *
 * The cup-stat fields back the [SummaryPanel.CUPS] panel and are always returned: [firstCupAt] (the user's
 * first cup, null if none), and the net [cupsThisWeek] / [cupsToday] since the start of the local week / day.
 */
data class UserSummaryDto(
    val count: Int,
    val priceCents: Int,
    val balanceCents: Long,
    val kittyBalanceCents: Long,
    val cancellable: Boolean,
    val summaryPanel: SummaryPanel,
    val firstCupAt: LocalDateTime?,
    val cupsThisWeek: Int,
    val cupsToday: Int,
    val activity: List<ActivityEntryDto>
)
