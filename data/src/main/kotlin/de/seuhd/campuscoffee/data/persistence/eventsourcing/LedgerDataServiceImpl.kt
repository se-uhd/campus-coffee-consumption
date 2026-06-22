package de.seuhd.campuscoffee.data.persistence.eventsourcing

import de.seuhd.campuscoffee.domain.model.CancellableIncrement
import de.seuhd.campuscoffee.domain.model.LedgerEntry
import de.seuhd.campuscoffee.domain.model.LedgerEntryType
import de.seuhd.campuscoffee.domain.model.PriceChange
import de.seuhd.campuscoffee.domain.ports.data.LedgerDataService
import org.springframework.stereotype.Service
import java.time.LocalDateTime
import java.util.UUID

private const val SYSTEM_ACTOR = "system"

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
 * The (private, kitty) portions of a split bean purchase, for the admin ledger breakdown, or `(null, null)`
 * when there is no split to show — a DELETE (which only reverses a balance) or an expense with no kitty
 * portion (a fully-private purchase). The two are surfaced together so both admin views (the member ledger's
 * PRIVATE_EXPENSE row and the kitty ledger's KITTY_EXPENSE row) render the same `private + kitty` split.
 */
private fun splitPortions(event: EventEntity): Pair<Long?, Long?> {
    if (event.changeType == ChangeType.DELETE || (intBody(event, "kittyAmountCents") ?: 0) <= 0) {
        return null to null
    }
    return (intBody(event, "privateAmountCents") ?: 0).toLong() to (intBody(event, "kittyAmountCents") ?: 0).toLong()
}

/** Reads a required UUID field from an event body. */
private fun uuidBody(
    event: EventEntity,
    key: String
): UUID = UUID.fromString(requireNotNull(event.body?.get(key)) { "An event body must carry $key." }.toString())

/** The event's append position. */
private fun seqOf(event: EventEntity): Long = requireNotNull(event.seq) { "A stored event must carry a seq." }

/** The event's recorded time. */
private fun createdAtOf(event: EventEntity): LocalDateTime =
    requireNotNull(event.createdAt) { "A stored event must carry a createdAt." }

/** The event's actor login, defaulting to the system actor. */
private fun actorOf(event: EventEntity): String = event.createdBy ?: SYSTEM_ACTOR

/**
 * The price in effect at append position [seq]: the latest price event at or before it. A price is always
 * seeded before any coffee is consumed (see the bootstrap), so this always resolves; a miss signals a bug.
 */
private fun priceAsOf(
    seq: Long,
    prices: List<PricePoint>
): Int =
    prices.lastOrNull { it.seq <= seq }?.amountCents
        ?: error(
            "No coffee price was in effect at event seq=$seq; a price must be seeded before any coffee is consumed."
        )

/**
 * Reads the unified-ledger and balance projections straight from the append-only event log (there is no
 * ledger table). It walks a member's streams oldest-first, valuing each coffee at [priceAsOf] its append
 * position, and tracks a per-member LIFO stack of increment prices so an undo credits exactly the increment
 * it reverses. Money sums use [Long]; per-event effects fit [Int].
 */
@Suppress("TooManyFunctions")
@Service
class LedgerDataServiceImpl(
    private val eventRepository: EventRepository
) : LedgerDataService {
    override fun memberLedger(
        userId: UUID,
        ownerLogin: String
    ): List<LedgerEntry> {
        val walk = MemberWalk(loadPricePoints(), ownerLogin)
        eventRepository.findMemberLedger(userId.toString()).forEach { walk.accept(it) }
        return walk.result()
    }

    override fun kittyLedger(): List<LedgerEntry> {
        val walk = KittyWalk()
        val payments = eventRepository.findByEntityTypeOrderBySeqAsc(LoggedEntityType.PAYMENT.label)
        val expenses = eventRepository.findByEntityTypeOrderBySeqAsc(LoggedEntityType.EXPENSE.label)
        (payments + expenses).sortedBy { seqOf(it) }.forEach { walk.accept(it) }
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
            .findMemberLedger(userId.toString())
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
                            CancellableIncrement(seqOf(event), createdAtOf(event), priceAsOf(seqOf(event), prices))
                        )
                    isOwnerStep && delta == -1 -> stack.removeLastOrNull()
                    // an admin override that lowers the count removes that many outstanding owner increments,
                    // so a member cannot undo a cup the admin removed or one the admin added; an override up
                    // adds non-undoable cups and pushes nothing
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
 * Accumulates one member's ledger oldest-first. Each accepted event contributes a signed effect to the
 * running [balance]; a coffee is valued at the price in effect at its append position, an owner undo credits
 * the exact price of the increment it pops off [incrementPrices], and an admin override is a lump at the
 * override-time price. Only the private portion of an expense and the member's own settlements affect the
 * balance.
 */
@Suppress("UndocumentedFunction", "UndocumentedFunctionParameter")
private class MemberWalk(
    private val prices: List<PricePoint>,
    private val ownerLogin: String
) {
    private var balance = 0L
    private var prevCount = 0
    private val incrementPrices = ArrayDeque<Int>()
    private val lastExpensePrivate = HashMap<UUID, Int>()
    private val lastSettlement = HashMap<UUID, Int>()
    private val entries = mutableListOf<LedgerEntry>()

    /** The accumulated ledger oldest-first. */
    fun result(): List<LedgerEntry> = entries

    /** Applies one of the member's events, appending its ledger entry (if it has any balance effect). */
    fun accept(event: EventEntity) {
        val entry =
            when (LoggedEntityType.ofLabel(requireNotNull(event.entityType))) {
                LoggedEntityType.COFFEE_CONSUMPTION -> consumption(event)
                LoggedEntityType.EXPENSE -> expense(event)
                LoggedEntityType.PAYMENT -> settlement(event)
                // a member's stream never carries these; listed (not `else`) so a new type forces a decision
                LoggedEntityType.USER, LoggedEntityType.COFFEE_PRICE -> null
            } ?: return
        balance += entry.amountCents
        entries += entry.copy(runningBalanceCents = balance)
    }

    private fun consumption(event: EventEntity): LedgerEntry? {
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
                entry(event, LedgerEntryType.CONSUMPTION, -price.toLong(), count = count, delta = delta)
            }
            byOwner && delta == -1 -> {
                // an owner -1 undoes one of the member's own +1s and normally pops its exact stacked price.
                // A missing one is reachable (e.g. dev fixtures seed coffees as the "system" actor, so an
                // owner undo of one finds no own increment on the stack), so credit the price in effect at the
                // undo's append position rather than failing the whole ledger read. An undo lands within the
                // short grace window of its increment, so the as-of price equals the increment price in
                // practice; this only differs in the contrived case the exact increment is genuinely absent.
                val price = incrementPrices.removeLastOrNull() ?: priceAsOf(seq, prices)
                entry(event, LedgerEntryType.CONSUMPTION_CANCEL, price.toLong(), count = count, delta = delta)
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
                entry(event, LedgerEntryType.CONSUMPTION, -delta.toLong() * price, count = count, delta = delta)
            }
        }
    }

    private fun expense(event: EventEntity): LedgerEntry? {
        val id = uuidBody(event, "id")
        val effect =
            when (requireNotNull(event.changeType)) {
                ChangeType.INSERT -> intBody(event, "privateAmountCents") ?: 0
                ChangeType.UPDATE -> (intBody(event, "privateAmountCents") ?: 0) - (lastExpensePrivate[id] ?: 0)
                ChangeType.DELETE -> -(lastExpensePrivate[id] ?: 0)
            }
        if (event.changeType ==
            ChangeType.DELETE
        ) {
            lastExpensePrivate[id] = 0
        } else {
            lastExpensePrivate[id] =
                intBody(event, "privateAmountCents") ?: 0
        }
        // a fully kitty-funded expense (no private portion) does not touch the member's balance
        if (effect == 0) {
            return null
        }
        // carry both portions of a split purchase so the admin ledger can break it down (the member-serving
        // read path nulls them before they leave the domain — see AccountingServiceImpl). The two are present
        // together only when there is a real kitty split; a DELETE reverses the balance and exposes none, and
        // a fully-private expense leaves both null.
        val (privatePortion, kittyPortion) = splitPortions(event)
        return entry(
            event,
            LedgerEntryType.PRIVATE_EXPENSE,
            effect.toLong(),
            weightGrams = intBody(event, "weightGrams"),
            privateAmountCents = privatePortion,
            kittyAmountCents = kittyPortion
        )
    }

    private fun settlement(event: EventEntity): LedgerEntry? {
        val id = uuidBody(event, "id")
        val effect =
            when (requireNotNull(event.changeType)) {
                ChangeType.INSERT -> intBody(event, "amountCents") ?: 0
                ChangeType.UPDATE -> (intBody(event, "amountCents") ?: 0) - (lastSettlement[id] ?: 0)
                ChangeType.DELETE -> -(lastSettlement[id] ?: 0)
            }
        if (event.changeType ==
            ChangeType.DELETE
        ) {
            lastSettlement[id] = 0
        } else {
            lastSettlement[id] = intBody(event, "amountCents") ?: 0
        }
        return entry(event, LedgerEntryType.SETTLEMENT, effect.toLong())
    }

    private fun entry(
        event: EventEntity,
        type: LedgerEntryType,
        amountCents: Long,
        count: Int? = null,
        delta: Int? = null,
        weightGrams: Int? = null,
        privateAmountCents: Long? = null,
        kittyAmountCents: Long? = null
    ) = LedgerEntry(
        type = type,
        seq = seqOf(event),
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
 * Accumulates the kitty ledger oldest-first: payments (settlements and adjustments) add money, the
 * kitty-funded portion of an expense removes it. Each accepted event contributes a signed effect to the
 * running kitty [balance].
 */
@Suppress("UndocumentedFunction", "UndocumentedFunctionParameter")
private class KittyWalk {
    private var balance = 0L
    private val lastPayment = HashMap<UUID, Int>()
    private val lastExpenseKitty = HashMap<UUID, Int>()
    private val entries = mutableListOf<LedgerEntry>()

    /** The accumulated kitty ledger oldest-first. */
    fun result(): List<LedgerEntry> = entries

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

    private fun payment(event: EventEntity): LedgerEntry {
        val id = uuidBody(event, "id")
        val type =
            if (event.body?.get("userId") !=
                null
            ) {
                LedgerEntryType.SETTLEMENT
            } else {
                LedgerEntryType.KITTY_ADJUSTMENT
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

    private fun expense(event: EventEntity): LedgerEntry? {
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
        // carry both portions so the admin kitty ledger renders the same `private + kitty` split as the member
        // ledger's expense row; a DELETE only reverses the kitty draw and exposes none.
        val (privatePortion, kittyPortion) = splitPortions(event)
        return entry(
            event,
            LedgerEntryType.KITTY_EXPENSE,
            effect.toLong(),
            weightGrams = intBody(event, "weightGrams"),
            privateAmountCents = privatePortion,
            kittyAmountCents = kittyPortion
        )
    }

    private fun entry(
        event: EventEntity,
        type: LedgerEntryType,
        amountCents: Long,
        weightGrams: Int? = null,
        privateAmountCents: Long? = null,
        kittyAmountCents: Long? = null
    ) = LedgerEntry(
        type = type,
        seq = seqOf(event),
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
