package de.seuhd.campuscoffee.data.persistence.projection
import de.seuhd.campuscoffee.data.persistence.entities.EventEntity
import de.seuhd.campuscoffee.data.persistence.entities.LoggedEntityType
import java.util.UUID

/**
 * The single shared walk over a `seq`-ordered event stream that backs all three activity views. It maintains
 * per-subject balances and increment-price LIFO stacks and the one kitty balance, valuing each coffee at the
 * price in effect at its append position ([priceAsOf] over the precomputed [prices] timeline, never the inline
 * price events). [accept] folds one event and returns its interpreted [EventProjection], or null when the event
 * changes nothing (a zero-delta consumption). It is fed a bounded per-user stream for the user feed, the
 * kitty stream for the kitty history, and the whole log for the global feed; keying every accumulator per
 * subject makes those views agree event-for-event with the previous per-user and kitty walks even when
 * users interleave.
 *
 * @param prices the price timeline, ascending by append position
 * @param subjectLogin the login of a subject id, to tell a user's own coffee (credited at the original price
 *   on undo) from an admin override; null for an unknown or hard-deleted subject (treated as never-owner) or
 *   where ownership is irrelevant (the kitty walk, which carries no consumptions)
 */
@Suppress("UndocumentedFunction", "UndocumentedFunctionParameter")
internal class EventReducer(
    private val prices: List<PricePoint>,
    private val subjectLogin: (UUID) -> String?
) {
    private val userBalances = HashMap<UUID, Long>()
    private var kittyBalance = 0L
    private val prevCount = HashMap<UUID, Int>()
    private val incrementPrices = HashMap<UUID, ArrayDeque<Int>>()
    private val lastExpensePrivate = HashMap<UUID, Int>()
    private val lastExpenseKitty = HashMap<UUID, Int>()
    private val lastPayment = HashMap<UUID, Int>()

    /** Folds one event into the accumulators and returns its record, or null if nothing changed. */
    fun accept(event: EventEntity): EventProjection? =
        when (LoggedEntityType.ofLabel(requireNotNull(event.entityType))) {
            LoggedEntityType.COFFEE_CONSUMPTION -> consumption(event)
            LoggedEntityType.EXPENSE -> expense(event)
            LoggedEntityType.PAYMENT -> payment(event)
            LoggedEntityType.COFFEE_PRICE -> priceChange(event)
            LoggedEntityType.COFFEE_RATING -> rating(event)
            // a User or bean event never reaches an activity stream (it carries no money and is not a user
            // action worth showing); listed (not `else`) so a new type forces a decision
            LoggedEntityType.USER, LoggedEntityType.COFFEE_BEAN -> null
        }

    private fun consumption(event: EventEntity): EventProjection? {
        val subject = uuidBody(event, "userId")
        val count = intBody(event, "count") ?: 0
        val delta = count - (prevCount[subject] ?: 0)
        prevCount[subject] = count
        if (delta == 0) {
            return null
        }
        val seq = seqOf(event)
        val stack = incrementPrices.getOrPut(subject) { ArrayDeque() }
        val byOwner = actorOf(event) == subjectLogin(subject)
        val (kind, effect) =
            when {
                byOwner && delta == 1 -> {
                    val price = priceAsOf(seq, prices)
                    stack.addLast(price)
                    EventProjectionType.CONSUMPTION to -price.toLong()
                }
                byOwner && delta == -1 -> {
                    // an owner undo normally pops its exact stacked price; a missing one (e.g. fixtures seeded
                    // a coffee as the system actor, so an owner undo finds no own increment) falls back to the
                    // as-of price, which equals the increment price within the short grace window in practice
                    val price = stack.removeLastOrNull() ?: priceAsOf(seq, prices)
                    EventProjectionType.CONSUMPTION_CANCEL to price.toLong()
                }
                else -> {
                    // an admin override (any delta): value the whole jump as a lump at the override-time price;
                    // a lowering override also drops that many outstanding owner increments so a later undo
                    // cannot credit a stale price and a user cannot undo an admin-added cup
                    val price = priceAsOf(seq, prices)
                    if (delta < 0) {
                        repeat(minOf(-delta, stack.size)) { stack.removeLast() }
                    }
                    EventProjectionType.CONSUMPTION to -delta.toLong() * price
                }
            }
        val balance = (userBalances[subject] ?: 0L) + effect
        userBalances[subject] = balance
        return EventProjection(
            event = event,
            kind = kind,
            subjectUserId = subject,
            userEffect = effect,
            userBalance = balance,
            kittyEffect = null,
            kittyBalance = null,
            count = count,
            delta = delta
        )
    }

    private fun expense(event: EventEntity): EventProjection {
        val subject = uuidBody(event, "buyerUserId")
        // compute both portions every time so each per-entity memory stays current even when one portion is 0
        val privateEffect = deltaEffect(event, "privateAmountCents", lastExpensePrivate)
        val kittyEffect = -deltaEffect(event, "kittyAmountCents", lastExpenseKitty)
        val userBalanceAfter =
            if (privateEffect != 0) {
                (userBalances[subject] ?: 0L).plus(privateEffect).also { userBalances[subject] = it }
            } else {
                null
            }
        val kittyBalanceAfter =
            if (kittyEffect != 0) {
                kittyBalance += kittyEffect
                kittyBalance
            } else {
                null
            }
        val (privatePortion, kittyPortion) = splitPortions(event)
        return EventProjection(
            event = event,
            kind = EventProjectionType.EXPENSE,
            subjectUserId = subject,
            userEffect = if (privateEffect != 0) privateEffect.toLong() else null,
            userBalance = userBalanceAfter,
            kittyEffect = if (kittyEffect != 0) kittyEffect.toLong() else null,
            kittyBalance = kittyBalanceAfter,
            weightGrams = intBody(event, "weightGrams"),
            privatePortion = privatePortion,
            kittyPortion = kittyPortion,
            beanId = optionalUuidBody(event, "beanId")
        )
    }

    private fun payment(event: EventEntity): EventProjection {
        val amountDelta = deltaEffect(event, "amountCents", lastPayment).toLong()
        val subject = optionalUuidBody(event, "userId")
        return if (subject != null) {
            // a deposit credits the user and feeds the kitty by the same amount
            val userBalanceAfter =
                (userBalances[subject] ?: 0L).plus(amountDelta).also { userBalances[subject] = it }
            kittyBalance += amountDelta
            EventProjection(
                event = event,
                kind = EventProjectionType.DEPOSIT,
                subjectUserId = subject,
                userEffect = amountDelta,
                userBalance = userBalanceAfter,
                kittyEffect = amountDelta,
                kittyBalance = kittyBalance
            )
        } else {
            kittyBalance += amountDelta
            EventProjection(
                event = event,
                kind = EventProjectionType.KITTY_ADJUSTMENT,
                subjectUserId = null,
                userEffect = null,
                userBalance = null,
                kittyEffect = amountDelta,
                kittyBalance = kittyBalance
            )
        }
    }

    private fun rating(event: EventEntity): EventProjection? {
        // an INSERT/UPDATE rating body carries the rater's userId; a DELETE (an undo) carries only the id, so
        // it has no userId and is skipped. A rating moves no balance, so it carries the subject's current
        // running balance unchanged (a zero effect) and the bean and value for display.
        val subject = optionalUuidBody(event, "userId") ?: return null
        val balance = userBalances[subject] ?: 0L
        return EventProjection(
            event = event,
            kind = EventProjectionType.RATING,
            subjectUserId = subject,
            userEffect = 0L,
            userBalance = balance,
            kittyEffect = null,
            kittyBalance = null,
            beanId = optionalUuidBody(event, "beanId"),
            ratingValue = intBody(event, "value")
        )
    }

    private fun priceChange(event: EventEntity): EventProjection =
        EventProjection(
            event = event,
            kind = EventProjectionType.PRICE_CHANGE,
            subjectUserId = null,
            userEffect = null,
            userBalance = null,
            kittyEffect = null,
            kittyBalance = null,
            priceAmountCents = intBody(event, "amountCents")
        )
}
