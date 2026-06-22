package de.seuhd.campuscoffee.domain.model

import java.time.LocalDateTime

/**
 * The kind of a [LedgerEntry]. The first four appear in a member's unified ledger; the last three appear in
 * the admin-only kitty ledger.
 */
enum class LedgerEntryType {
    /** A coffee was consumed (a member `+1`, or an admin count override). */
    CONSUMPTION,

    /** A member undid a recent coffee within the grace period (reverses the matching increment). */
    CONSUMPTION_CANCEL,

    /** A member's own (private) bean purchase, which credits their balance. */
    PRIVATE_EXPENSE,

    /** A member paid money in (a settlement), which credits their balance and feeds the kitty. */
    SETTLEMENT,

    /** The kitty-funded portion of a bean purchase, which draws the kitty down. */
    KITTY_EXPENSE,

    /** An admin adjustment of the kitty (an initial float or a correction). */
    KITTY_ADJUSTMENT
}

/**
 * One row of a unified ledger, reconstructed from a single event-log row (there is no ledger table). It is
 * a read-only projection: it carries no identifier.
 *
 * [amountCents] is the signed effect this entry has on the running balance it belongs to — a member's
 * balance for a member ledger, or the kitty for the kitty ledger — and [runningBalanceCents] is that
 * balance after this entry (computed by walking the stream oldest-first). Both are `Long` so a large admin
 * override (count × price) cannot overflow. [seq] is the event's append order; [createdAt]/[createdBy]/[note]
 * are the event metadata. The remaining fields are populated only where they apply: [count]/[delta] for a
 * consumption entry, [weightGrams] for an expense entry, and [privateAmountCents]/[kittyAmountCents] for an
 * admin-split expense (its two portions, shown only on the admin views — see below).
 *
 * [privateAmountCents] and [kittyAmountCents] are the member-funded and kitty-funded portions of a split
 * bean purchase. They are present together, only on an expense that an admin split between the member and the
 * kitty — a [LedgerEntryType.PRIVATE_EXPENSE] on the member ledger and a [LedgerEntryType.KITTY_EXPENSE] on
 * the kitty ledger both carry the same two portions — and both are null for an unsplit expense (fully private
 * or fully kitty) and every non-expense entry. They are for the **admin** views only: the split must never
 * reach a member, so the member-serving read path nulls both (a member's purchases read as 100% private).
 * They never touch [amountCents]/[runningBalanceCents], which stay the entry's own signed effect alone.
 */
data class LedgerEntry(
    val type: LedgerEntryType,
    val seq: Long,
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
