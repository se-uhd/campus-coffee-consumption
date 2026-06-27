package de.seuhd.campuscoffee.domain.model

import java.time.LocalDateTime
import java.util.UUID

/**
 * One row of the unified activity feed, reconstructed from a single event-log row (there is no activity
 * table); a read-only projection. Its [id] is the id of the event it was reconstructed from, a stable
 * per-entry key a client can use to page and deduplicate; it deliberately is not the event-store append
 * position (that ordering detail stays inside the data layer).
 *
 * [amountCents] is the signed effect this entry has on the running balance it belongs to (a user's balance
 * for a user's activity feed, or the kitty for the kitty history), and [runningBalanceCents] is that balance
 * after this entry (computed by walking the stream oldest-first). Both are `Long` so a large admin override
 * (count × price) cannot overflow. [createdAt]/[createdBy]/[note] are the event metadata. The remaining
 * fields are populated only where they apply: [count]/[delta] for a consumption entry, [weightGrams] for an
 * expense entry, and [privateAmountCents]/[kittyAmountCents] for an admin-split expense (its two portions,
 * shown only on the admin views, see below).
 *
 * [privateAmountCents] and [kittyAmountCents] are the user-funded and kitty-funded portions of a split bean
 * purchase. They are present together, only on an expense that an admin split between the user and the kitty
 * (an [ActivityEntryType.PRIVATE_EXPENSE] on a user's activity feed and an [ActivityEntryType.KITTY_EXPENSE]
 * on the kitty history both carry the same two portions), and both are null for an unsplit expense (fully
 * private or fully kitty) and every non-expense entry. They are for the **admin** views only: the split must
 * never reach a user, so the user-serving read path nulls both (a user's purchases read as 100% private).
 * They never touch [amountCents]/[runningBalanceCents], which stay the entry's own signed effect alone.
 */
data class ActivityEntry(
    val type: ActivityEntryType,
    val id: UUID,
    val createdAt: LocalDateTime,
    val createdBy: String,
    val note: String?,
    val amountCents: Long,
    val runningBalanceCents: Long,
    val count: Int? = null,
    val delta: Int? = null,
    val weightGrams: Int? = null,
    val privateAmountCents: Long? = null,
    val kittyAmountCents: Long? = null
)
