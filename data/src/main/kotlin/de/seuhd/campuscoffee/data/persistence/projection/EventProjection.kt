package de.seuhd.campuscoffee.data.persistence.projection
import de.seuhd.campuscoffee.data.persistence.entities.EventEntity
import de.seuhd.campuscoffee.domain.model.ActivityEntry
import de.seuhd.campuscoffee.domain.model.ActivityEntryType
import de.seuhd.campuscoffee.domain.model.GlobalActivityEntry
import java.util.UUID

/**
 * One event interpreted by [EventReducer]: the subject user it concerns and its signed effect on that
 * user's balance and/or the kitty, with both running balances after, plus the metadata each view needs. A
 * field is null where the event does not touch it (no user effect, no kitty effect, no count, and so on).
 * Each view maps a record to its public shape and assigns the `ActivityEntryType` from [kind].
 */
internal data class EventProjection(
    val event: EventEntity,
    val kind: EventProjectionType,
    val subjectUserId: UUID?,
    val userEffect: Long?,
    val userBalance: Long?,
    val kittyEffect: Long?,
    val kittyBalance: Long?,
    val count: Int? = null,
    val delta: Int? = null,
    val weightGrams: Int? = null,
    val privatePortion: Long? = null,
    val kittyPortion: Long? = null,
    val priceAmountCents: Int? = null
)

/**
 * Maps a walked record to a user-feed entry (the user's running balance). Only reached for records with a
 * user effect on a user's own stream, so the kitty-only kinds never occur.
 */
internal fun EventProjection.toUserEntry(): ActivityEntry =
    ActivityEntry(
        type =
            when (kind) {
                EventProjectionType.CONSUMPTION -> ActivityEntryType.CONSUMPTION
                EventProjectionType.CONSUMPTION_CANCEL -> ActivityEntryType.CONSUMPTION_CANCEL
                EventProjectionType.EXPENSE -> ActivityEntryType.PRIVATE_EXPENSE
                EventProjectionType.DEPOSIT -> ActivityEntryType.DEPOSIT
                EventProjectionType.KITTY_ADJUSTMENT, EventProjectionType.PRICE_CHANGE ->
                    error("A user activity feed never carries a $kind record.")
            },
        id = idOf(event),
        createdAt = createdAtOf(event),
        createdBy = actorOf(event),
        note = event.note,
        amountCents = requireNotNull(userEffect),
        runningBalanceCents = requireNotNull(userBalance),
        count = count,
        delta = delta,
        weightGrams = weightGrams,
        privateAmountCents = privatePortion,
        kittyAmountCents = kittyPortion
    )

/**
 * Maps a walked record to a kitty-history entry (the kitty running balance). Only reached for records with a
 * kitty effect on the kitty stream, so the consumption and price kinds never occur.
 */
internal fun EventProjection.toKittyEntry(): ActivityEntry =
    ActivityEntry(
        type =
            when (kind) {
                EventProjectionType.DEPOSIT -> ActivityEntryType.DEPOSIT
                EventProjectionType.KITTY_ADJUSTMENT -> ActivityEntryType.KITTY_ADJUSTMENT
                EventProjectionType.EXPENSE -> ActivityEntryType.KITTY_EXPENSE
                EventProjectionType.CONSUMPTION,
                EventProjectionType.CONSUMPTION_CANCEL,
                EventProjectionType.PRICE_CHANGE ->
                    error("A kitty history never carries a $kind record.")
            },
        id = idOf(event),
        createdAt = createdAtOf(event),
        createdBy = actorOf(event),
        note = event.note,
        amountCents = requireNotNull(kittyEffect),
        runningBalanceCents = requireNotNull(kittyBalance),
        weightGrams = weightGrams,
        privateAmountCents = privatePortion,
        kittyAmountCents = kittyPortion
    )

/**
 * Maps a walked record to a global-feed entry carrying both balances. An expense is one row of type
 * `PRIVATE_EXPENSE` showing whichever of the user and kitty effects it moved. The subject login is stamped
 * from [subjectLoginById]; the display name is left for the domain to resolve.
 *
 * @param subjectLoginById the login of each known user by id (a subject absent from it is hard-deleted)
 */
internal fun EventProjection.toGlobalEntry(subjectLoginById: Map<UUID, String>): GlobalActivityEntry =
    GlobalActivityEntry(
        type =
            when (kind) {
                EventProjectionType.CONSUMPTION -> ActivityEntryType.CONSUMPTION
                EventProjectionType.CONSUMPTION_CANCEL -> ActivityEntryType.CONSUMPTION_CANCEL
                EventProjectionType.EXPENSE -> ActivityEntryType.PRIVATE_EXPENSE
                EventProjectionType.DEPOSIT -> ActivityEntryType.DEPOSIT
                EventProjectionType.KITTY_ADJUSTMENT -> ActivityEntryType.KITTY_ADJUSTMENT
                EventProjectionType.PRICE_CHANGE -> ActivityEntryType.PRICE_CHANGE
            },
        id = idOf(event),
        createdAt = createdAtOf(event),
        actorLogin = actorOf(event),
        subjectUserId = subjectUserId,
        subjectLogin = subjectUserId?.let { subjectLoginById[it] },
        subjectName = null,
        note = event.note,
        userEffectCents = userEffect,
        userBalanceCents = userBalance,
        kittyEffectCents = kittyEffect,
        kittyBalanceCents = kittyBalance,
        count = count,
        delta = delta,
        weightGrams = weightGrams,
        privateAmountCents = privatePortion,
        kittyAmountCents = kittyPortion,
        priceAmountCents = priceAmountCents
    )
