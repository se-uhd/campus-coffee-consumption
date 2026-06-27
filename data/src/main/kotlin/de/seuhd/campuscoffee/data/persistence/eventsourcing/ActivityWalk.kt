package de.seuhd.campuscoffee.data.persistence.eventsourcing

import java.util.UUID

/**
 * The single shared walk over a `seq`-ordered event stream that backs all three activity views. It maintains
 * per-subject balances and increment-price LIFO stacks and the one kitty balance, valuing each coffee at the
 * price in effect at its append position ([priceAsOf] over the precomputed [prices] timeline, never the inline
 * price events). [accept] folds one event and returns its interpreted [WalkedRecord], or null when the event
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
internal class ActivityWalk(
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
    fun accept(event: EventEntity): WalkedRecord? =
        when (LoggedEntityType.ofLabel(requireNotNull(event.entityType))) {
            LoggedEntityType.COFFEE_CONSUMPTION -> consumption(event)
            LoggedEntityType.EXPENSE -> expense(event)
            LoggedEntityType.PAYMENT -> payment(event)
            LoggedEntityType.COFFEE_PRICE -> priceChange(event)
            // a User event never reaches an activity stream; listed (not `else`) so a new type forces a decision
            LoggedEntityType.USER -> null
        }

    private fun consumption(event: EventEntity): WalkedRecord? {
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
                    WalkedKind.CONSUMPTION to -price.toLong()
                }
                byOwner && delta == -1 -> {
                    // an owner undo normally pops its exact stacked price; a missing one (e.g. fixtures seeded
                    // a coffee as the system actor, so an owner undo finds no own increment) falls back to the
                    // as-of price, which equals the increment price within the short grace window in practice
                    val price = stack.removeLastOrNull() ?: priceAsOf(seq, prices)
                    WalkedKind.CONSUMPTION_CANCEL to price.toLong()
                }
                else -> {
                    // an admin override (any delta): value the whole jump as a lump at the override-time price;
                    // a lowering override also drops that many outstanding owner increments so a later undo
                    // cannot credit a stale price and a user cannot undo an admin-added cup
                    val price = priceAsOf(seq, prices)
                    if (delta < 0) {
                        repeat(minOf(-delta, stack.size)) { stack.removeLast() }
                    }
                    WalkedKind.CONSUMPTION to -delta.toLong() * price
                }
            }
        val balance = (userBalances[subject] ?: 0L) + effect
        userBalances[subject] = balance
        return WalkedRecord(
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

    private fun expense(event: EventEntity): WalkedRecord {
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
        return WalkedRecord(
            event = event,
            kind = WalkedKind.EXPENSE,
            subjectUserId = subject,
            userEffect = if (privateEffect != 0) privateEffect.toLong() else null,
            userBalance = userBalanceAfter,
            kittyEffect = if (kittyEffect != 0) kittyEffect.toLong() else null,
            kittyBalance = kittyBalanceAfter,
            weightGrams = intBody(event, "weightGrams"),
            privatePortion = privatePortion,
            kittyPortion = kittyPortion
        )
    }

    private fun payment(event: EventEntity): WalkedRecord {
        val amountDelta = deltaEffect(event, "amountCents", lastPayment).toLong()
        val subject = optionalUuidBody(event, "userId")
        return if (subject != null) {
            // a deposit credits the user and feeds the kitty by the same amount
            val userBalanceAfter =
                (userBalances[subject] ?: 0L).plus(amountDelta).also { userBalances[subject] = it }
            kittyBalance += amountDelta
            WalkedRecord(
                event = event,
                kind = WalkedKind.DEPOSIT,
                subjectUserId = subject,
                userEffect = amountDelta,
                userBalance = userBalanceAfter,
                kittyEffect = amountDelta,
                kittyBalance = kittyBalance
            )
        } else {
            kittyBalance += amountDelta
            WalkedRecord(
                event = event,
                kind = WalkedKind.KITTY_ADJUSTMENT,
                subjectUserId = null,
                userEffect = null,
                userBalance = null,
                kittyEffect = amountDelta,
                kittyBalance = kittyBalance
            )
        }
    }

    private fun priceChange(event: EventEntity): WalkedRecord =
        WalkedRecord(
            event = event,
            kind = WalkedKind.PRICE_CHANGE,
            subjectUserId = null,
            userEffect = null,
            userBalance = null,
            kittyEffect = null,
            kittyBalance = null,
            priceAmountCents = intBody(event, "amountCents")
        )
}
