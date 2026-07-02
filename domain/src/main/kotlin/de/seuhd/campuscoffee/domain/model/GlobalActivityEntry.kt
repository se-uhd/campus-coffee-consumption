package de.seuhd.campuscoffee.domain.model

import java.time.LocalDateTime
import java.util.UUID

/**
 * One row of the admin-only **global** activity feed: every change across all users, the kitty, and the
 * price, reconstructed from a single event-log row (there is no activity table). It is a read-only projection,
 * the all-users analogue of [ActivityEntry] but carrying two running balances and two users, because one
 * event can move both a user's balance and the kitty (a deposit, a split bean purchase).
 *
 * Each row records who it concerns ([subjectUserId]/[subjectLogin]/[subjectName], the user whose balance or
 * count moved) and who performed it ([actorLogin], the event's `created_by`). For a user self-scan the two
 * coincide; for an admin correction they differ. The subject is null for an event that concerns no single
 * user (a pure kitty adjustment or a price change); for a hard-deleted user whose events outlive them, the
 * subject id remains but the login does not resolve, so [subjectLogin] is null and [subjectName] reads
 * `(deleted user)`.
 *
 * [userEffectCents]/[userBalanceCents] are this event's signed effect on the subject user's balance and
 * that balance afterwards, both null when the event moves no user balance (a kitty adjustment, a price
 * change, a fully kitty-funded expense). [kittyEffectCents]/[kittyBalanceCents] are the same for the kitty,
 * null when the event does not touch it (a coffee, a fully private expense, a price change). The remaining
 * fields are populated only where they apply: [count]/[delta] for a consumption, [weightGrams] for an expense,
 * [privateAmountCents]/[kittyAmountCents] for the split of an admin bean purchase, and [priceAmountCents] for a
 * [ActivityEntryType.PRICE_CHANGE] row (the new price).
 */
data class GlobalActivityEntry(
    val type: ActivityEntryType,
    val id: UUID,
    val createdAt: LocalDateTime,
    val actorLogin: String,
    val subjectUserId: UUID?,
    val subjectLogin: String?,
    val subjectName: String?,
    val note: String?,
    val userEffectCents: Long?,
    val userBalanceCents: Long?,
    val kittyEffectCents: Long?,
    val kittyBalanceCents: Long?,
    val count: Int? = null,
    val delta: Int? = null,
    val weightGrams: Int? = null,
    val privateAmountCents: Long? = null,
    val kittyAmountCents: Long? = null,
    val priceAmountCents: Int? = null,
    val beanName: String? = null,
    val ratingValue: Int? = null
)
