package de.seuhd.campuscoffee.data.persistence.eventsourcing

import de.seuhd.campuscoffee.domain.model.ActivityEntry
import de.seuhd.campuscoffee.domain.model.ActivityEntryType
import de.seuhd.campuscoffee.domain.model.CancellableIncrement
import de.seuhd.campuscoffee.domain.model.PriceChange
import de.seuhd.campuscoffee.domain.ports.data.ActivityDataService
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import java.time.LocalDateTime
import java.util.UUID

private const val SYSTEM_ACTOR = "system"

private val log = KotlinLogging.logger {}

/** A point on the price timeline: the price that took effect at append position [seq]. */
private data class PricePoint(
    val seq: Long,
    val amountCents: Int
)

/** Reads an integer field from an event body, tolerating Int/Long/BigInteger decoding; null if absent. */
private fun intBody(
    event: EventEntity,
    key: String
): Int? = (event.body?.get(key) as? Number)?.toInt()

/**
 * The (private, kitty) portions of a split bean purchase, for the admin activity breakdown, or `(null, null)`
 * when there is no split to show: a DELETE (which only reverses a balance) or an expense with no kitty
 * portion (a fully-private purchase). The two are surfaced together so both admin views (the member activity's
 * PRIVATE_EXPENSE row and the kitty history's KITTY_EXPENSE row) render the same `private + kitty` split.
 */
private fun splitPortions(event: EventEntity): Pair<Long?, Long?> {
    // Only an INSERT (the original purchase) carries an absolute split worth displaying. An UPDATE row's
    // amountCents is a delta (new minus old) and a DELETE only reverses the balance, so an absolute
    // private/kitty breakdown shown beside a delta amount could not be reconciled by a reader; those rows
    // show only their signed effect.
    if (event.changeType != ChangeType.INSERT || (intBody(event, "kittyAmountCents") ?: 0) <= 0) {
        return null to null
    }
    return (intBody(event, "privateAmountCents") ?: 0).toLong() to (intBody(event, "kittyAmountCents") ?: 0).toLong()
}

/** Reads a required UUID field from an event body. */
private fun uuidBody(
    event: EventEntity,
    key: String
): UUID = UUID.fromString(requireNotNull(event.body?.get(key)) { "An event body must carry $key." }.toString())

/**
 * The event's append position. `seq` is assigned by the identity column at INSERT, not at commit, and price
 * changes and `+1` consumptions are not serialized by a lock, so two concurrent unlocked transactions could
 * in principle acquire `seq` in one order and commit in the other. A coffee consumed in the same instant a
 * price change commits could then be valued at the old or new price in the maintained member_balance until
 * the next write for that member recomputes it from the log and corrects it. In practice a price change does
 * not interleave with self-scans, so this window is not reached; the kitty-overdraw path that must not race
 * is separately serialized by the advisory lock.
 */
private fun seqOf(event: EventEntity): Long = requireNotNull(event.seq) { "A stored event must carry a seq." }

/**
 * The event's own id, used as an activity entry's stable per-entry key (for client-side paging and dedup). The
 * append position [seqOf] orders the walk but stays inside the data layer; the entry exposes this id instead.
 */
private fun idOf(event: EventEntity): UUID = requireNotNull(event.id) { "A stored event must carry an id." }

/** The event's recorded time. */
private fun createdAtOf(event: EventEntity): LocalDateTime =
    requireNotNull(event.createdAt) { "A stored event must carry a createdAt." }

/** The event's actor login, defaulting to the system actor. */
private fun actorOf(event: EventEntity): String = event.createdBy ?: SYSTEM_ACTOR

/**
 * The signed balance effect of a value-carrying event (an expense's private portion, or a deposit
 * amount): the full value on INSERT, the change from the remembered previous value on UPDATE, and the
 * reversal on DELETE. Updates [last] for the entity so the next UPDATE/DELETE of the same entity sees the
 * right previous value (a DELETE resets it to zero). Shared by the expense and deposit walks, which
 * differ only in the body field and the per-entity memory.
 *
 * @param event the event being walked
 * @param key the body field holding the value (`privateAmountCents` or `amountCents`)
 * @param last the per-entity memory of the last value, keyed on the body id
 */
private fun deltaEffect(
    event: EventEntity,
    key: String,
    last: MutableMap<UUID, Int>
): Int {
    val id = uuidBody(event, "id")
    val current = intBody(event, key) ?: 0
    val effect =
        when (requireNotNull(event.changeType)) {
            ChangeType.INSERT -> current
            ChangeType.UPDATE -> current - (last[id] ?: 0)
            ChangeType.DELETE -> -(last[id] ?: 0)
        }
    last[id] = if (event.changeType == ChangeType.DELETE) 0 else current
    return effect
}

/**
 * The price in effect at append position [seq]: the latest price event at or before it. A price is always
 * seeded before any coffee is consumed (see the bootstrap), so the as-of lookup normally resolves. If a
 * hand-edited or misordered log somehow holds a coffee before any price, fall back to the earliest known
 * price (never 0) rather than throwing, so one malformed member stream cannot 500 a whole admin overview or
 * activity read. Only an empty price history falls back to 0, and that case is logged at warn level so a
 * coffee charged nothing is not silent.
 */
private fun priceAsOf(
    seq: Long,
    prices: List<PricePoint>
): Int {
    val resolved = prices.lastOrNull { it.seq <= seq }?.amountCents ?: prices.firstOrNull()?.amountCents
    if (resolved == null) {
        log.warn { "No price exists in the log; valuing a coffee at seq $seq as 0 cents. The price seed is missing." }
        return 0
    }
    return resolved
}

/**
 * Reads the unified-activity and balance projections straight from the append-only event log (there is no
 * activity table). It walks a member's streams oldest-first, valuing each coffee at [priceAsOf] its append
 * position, and tracks a per-member LIFO stack of increment prices so an undo credits exactly the increment
 * it reverses. Money sums use [Long]; per-event effects fit [Int].
 */
@Suppress("TooManyFunctions")
@Service
class ActivityDataServiceImpl(
    private val eventRepository: EventRepository
) : ActivityDataService {
    override fun userActivity(
        userId: UUID,
        ownerLogin: String
    ): List<ActivityEntry> {
        val walk = UserWalk(loadPricePoints(), ownerLogin)
        eventRepository.findUserActivity(userId.toString()).forEach { walk.accept(it) }
        return walk.result()
    }

    override fun kittyHistory(): List<ActivityEntry> {
        val walk = KittyWalk()
        // one SQL-ordered stream (payments + expenses by seq) instead of two type queries re-sorted in memory
        eventRepository.findKittyStream().forEach { walk.accept(it) }
        return walk.result()
    }

    override fun lastCancellableIncrement(
        userId: UUID,
        ownerLogin: String
    ): CancellableIncrement? {
        val prices = loadPricePoints()
        val stack = ArrayDeque<CancellableIncrement>()
        var prevCount = 0
        eventRepository
            .findUserActivity(userId.toString())
            .filter { LoggedEntityType.ofLabel(requireNotNull(it.entityType)) == LoggedEntityType.COFFEE_CONSUMPTION }
            .forEach { event ->
                val count = intBody(event, "count") ?: 0
                val delta = count - prevCount
                prevCount = count
                if (delta == 0) return@forEach
                val isOwnerStep = actorOf(event) == ownerLogin
                when {
                    isOwnerStep && delta == 1 ->
                        stack.addLast(
                            CancellableIncrement(createdAtOf(event), priceAsOf(seqOf(event), prices))
                        )
                    isOwnerStep && delta == -1 -> stack.removeLastOrNull()
                    // any admin step down removes that many outstanding owner increments, so a member cannot
                    // undo a cup the admin removed or one the admin added. This includes an admin single-step
                    // -1: it credits the member, so leaving their pending undo would let the same cup be
                    // credited twice. The intended effect is that an admin -1 also clears the member's undo.
                    // An admin step up adds non-undoable cups and pushes nothing.
                    delta < 0 -> repeat(minOf(-delta, stack.size)) { stack.removeLast() }
                }
            }
        return stack.lastOrNull()
    }

    override fun priceHistory(): List<PriceChange> =
        eventRepository.findByEntityTypeOrderBySeqAsc(LoggedEntityType.COFFEE_PRICE.label).map {
            PriceChange(
                amountCents = intBody(it, "amountCents") ?: 0,
                createdAt = createdAtOf(it),
                createdBy = actorOf(it)
            )
        }

    /** Loads the price timeline once, ascending by append position. */
    private fun loadPricePoints(): List<PricePoint> =
        eventRepository
            .findByEntityTypeOrderBySeqAsc(LoggedEntityType.COFFEE_PRICE.label)
            .map { PricePoint(seqOf(it), intBody(it, "amountCents") ?: 0) }
}

/**
 * Accumulates one member's activity oldest-first. Each accepted event contributes a signed effect to the
 * running [balance]; a coffee is valued at the price in effect at its append position, an owner undo credits
 * the exact price of the increment it pops off [incrementPrices], and an admin override is a lump at the
 * override-time price. Because the override is valued at the override-time price (not the per-cup prices
 * actually charged), a correction down made after a price change may not net the member's balance exactly to
 * zero; that is the documented intended rule, not a rounding bug. Only the private portion of an expense and
 * the member's own deposits affect the balance.
 */
@Suppress("UndocumentedFunction", "UndocumentedFunctionParameter")
private class UserWalk(
    private val prices: List<PricePoint>,
    private val ownerLogin: String
) {
    private var balance = 0L
    private var prevCount = 0
    private val incrementPrices = ArrayDeque<Int>()
    private val lastExpensePrivate = HashMap<UUID, Int>()
    private val lastDeposit = HashMap<UUID, Int>()
    private val entries = mutableListOf<ActivityEntry>()

    /** The accumulated activity oldest-first. */
    fun result(): List<ActivityEntry> = entries

    /** Applies one of the member's events, appending its activity entry (if it has any balance effect). */
    fun accept(event: EventEntity) {
        val entry =
            when (LoggedEntityType.ofLabel(requireNotNull(event.entityType))) {
                LoggedEntityType.COFFEE_CONSUMPTION -> consumption(event)
                LoggedEntityType.EXPENSE -> expense(event)
                LoggedEntityType.PAYMENT -> deposit(event)
                // a member's stream never carries these; listed (not `else`) so a new type forces a decision
                LoggedEntityType.USER, LoggedEntityType.COFFEE_PRICE -> null
            } ?: return
        balance += entry.amountCents
        entries += entry.copy(runningBalanceCents = balance)
    }

    private fun consumption(event: EventEntity): ActivityEntry? {
        val count = intBody(event, "count") ?: 0
        val delta = count - prevCount
        prevCount = count
        if (delta == 0) {
            return null
        }
        val seq = seqOf(event)
        val byOwner = actorOf(event) == ownerLogin
        return when {
            byOwner && delta == 1 -> {
                val price = priceAsOf(seq, prices)
                incrementPrices.addLast(price)
                entry(event, ActivityEntryType.CONSUMPTION, -price.toLong(), count = count, delta = delta)
            }
            byOwner && delta == -1 -> {
                // an owner -1 undoes one of the member's own +1s and normally pops its exact stacked price.
                // A missing one is reachable (e.g. dev fixtures seed coffees as the "system" actor, so an
                // owner undo of one finds no own increment on the stack), so credit the price in effect at the
                // undo's append position rather than failing the whole activity read. An undo lands within the
                // short grace window of its increment, so the as-of price equals the increment price in
                // practice; this only differs in the contrived case the exact increment is genuinely absent.
                val price = incrementPrices.removeLastOrNull() ?: priceAsOf(seq, prices)
                entry(event, ActivityEntryType.CONSUMPTION_CANCEL, price.toLong(), count = count, delta = delta)
            }
            else -> {
                // an admin override (any delta): value the whole jump as a lump at the override-time price
                // (Long, so a large override cannot overflow). An override that lowers the count also removes
                // that many outstanding owner increments from the undo stack, so a later undo cannot credit a
                // stale price and a member cannot undo an admin-added cup.
                val price = priceAsOf(seq, prices)
                if (delta < 0) {
                    repeat(minOf(-delta, incrementPrices.size)) { incrementPrices.removeLast() }
                }
                entry(event, ActivityEntryType.CONSUMPTION, -delta.toLong() * price, count = count, delta = delta)
            }
        }
    }

    private fun expense(event: EventEntity): ActivityEntry? {
        val effect = deltaEffect(event, "privateAmountCents", lastExpensePrivate)
        // a fully kitty-funded expense (no private portion) does not touch the member's balance
        if (effect == 0) {
            return null
        }
        // carry both portions of a split purchase so the admin activity can break it down (the member-serving
        // read path nulls them before they leave the domain, see AccountingServiceImpl). The two are present
        // together only when there is a real kitty split; a DELETE reverses the balance and exposes none, and
        // a fully-private expense leaves both null.
        val (privatePortion, kittyPortion) = splitPortions(event)
        return entry(
            event,
            ActivityEntryType.PRIVATE_EXPENSE,
            effect.toLong(),
            weightGrams = intBody(event, "weightGrams"),
            privateAmountCents = privatePortion,
            kittyAmountCents = kittyPortion
        )
    }

    private fun deposit(event: EventEntity): ActivityEntry {
        val effect = deltaEffect(event, "amountCents", lastDeposit)
        return entry(event, ActivityEntryType.DEPOSIT, effect.toLong())
    }

    private fun entry(
        event: EventEntity,
        type: ActivityEntryType,
        amountCents: Long,
        count: Int? = null,
        delta: Int? = null,
        weightGrams: Int? = null,
        privateAmountCents: Long? = null,
        kittyAmountCents: Long? = null
    ) = ActivityEntry(
        type = type,
        id = idOf(event),
        createdAt = createdAtOf(event),
        createdBy = actorOf(event),
        note = event.note,
        amountCents = amountCents,
        runningBalanceCents = 0,
        count = count,
        delta = delta,
        weightGrams = weightGrams,
        privateAmountCents = privateAmountCents,
        kittyAmountCents = kittyAmountCents
    )
}

/**
 * Accumulates the kitty history oldest-first: payments (deposits and adjustments) add money, the
 * kitty-funded portion of an expense removes it. Each accepted event contributes a signed effect to the
 * running kitty [balance].
 */
@Suppress("UndocumentedFunction", "UndocumentedFunctionParameter")
private class KittyWalk {
    private var balance = 0L
    private val lastPayment = HashMap<UUID, Int>()
    private val lastExpenseKitty = HashMap<UUID, Int>()
    private val entries = mutableListOf<ActivityEntry>()

    /** The accumulated kitty history oldest-first. */
    fun result(): List<ActivityEntry> = entries

    /** Applies one money-stream event, appending its kitty entry (if it moves the kitty). */
    fun accept(event: EventEntity) {
        val entry =
            when (LoggedEntityType.ofLabel(requireNotNull(event.entityType))) {
                LoggedEntityType.PAYMENT -> payment(event)
                LoggedEntityType.EXPENSE -> expense(event)
                // the kitty stream never carries these; listed (not `else`) so a new type forces a decision
                LoggedEntityType.USER, LoggedEntityType.COFFEE_CONSUMPTION, LoggedEntityType.COFFEE_PRICE -> null
            } ?: return
        balance += entry.amountCents
        entries += entry.copy(runningBalanceCents = balance)
    }

    private fun payment(event: EventEntity): ActivityEntry {
        val id = uuidBody(event, "id")
        val type =
            if (event.body?.get("userId") !=
                null
            ) {
                ActivityEntryType.DEPOSIT
            } else {
                ActivityEntryType.KITTY_ADJUSTMENT
            }
        val effect =
            when (requireNotNull(event.changeType)) {
                ChangeType.INSERT -> intBody(event, "amountCents") ?: 0
                ChangeType.UPDATE -> (intBody(event, "amountCents") ?: 0) - (lastPayment[id] ?: 0)
                ChangeType.DELETE -> -(lastPayment[id] ?: 0)
            }
        if (event.changeType ==
            ChangeType.DELETE
        ) {
            lastPayment[id] = 0
        } else {
            lastPayment[id] = intBody(event, "amountCents") ?: 0
        }
        return entry(event, type, effect.toLong())
    }

    private fun expense(event: EventEntity): ActivityEntry? {
        val id = uuidBody(event, "id")
        val effect =
            when (requireNotNull(event.changeType)) {
                ChangeType.INSERT -> -(intBody(event, "kittyAmountCents") ?: 0)
                ChangeType.UPDATE -> -((intBody(event, "kittyAmountCents") ?: 0) - (lastExpenseKitty[id] ?: 0))
                ChangeType.DELETE -> lastExpenseKitty[id] ?: 0
            }
        if (event.changeType ==
            ChangeType.DELETE
        ) {
            lastExpenseKitty[id] = 0
        } else {
            lastExpenseKitty[id] =
                intBody(event, "kittyAmountCents") ?: 0
        }
        // a private-only expense (no kitty portion) does not move the kitty
        if (effect == 0) {
            return null
        }
        // carry both portions so the admin kitty history renders the same `private + kitty` split as the member
        // activity's expense row; a DELETE only reverses the kitty draw and exposes none.
        val (privatePortion, kittyPortion) = splitPortions(event)
        return entry(
            event,
            ActivityEntryType.KITTY_EXPENSE,
            effect.toLong(),
            weightGrams = intBody(event, "weightGrams"),
            privateAmountCents = privatePortion,
            kittyAmountCents = kittyPortion
        )
    }

    private fun entry(
        event: EventEntity,
        type: ActivityEntryType,
        amountCents: Long,
        weightGrams: Int? = null,
        privateAmountCents: Long? = null,
        kittyAmountCents: Long? = null
    ) = ActivityEntry(
        type = type,
        id = idOf(event),
        createdAt = createdAtOf(event),
        createdBy = actorOf(event),
        note = event.note,
        amountCents = amountCents,
        runningBalanceCents = 0,
        weightGrams = weightGrams,
        privateAmountCents = privateAmountCents,
        kittyAmountCents = kittyAmountCents
    )
}
