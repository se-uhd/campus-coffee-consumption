@file:Suppress("TooManyFunctions")

package de.seuhd.campuscoffee.data.persistence.eventsourcing

import de.seuhd.campuscoffee.domain.model.ActivityEntry
import de.seuhd.campuscoffee.domain.model.ActivityEntryType
import de.seuhd.campuscoffee.domain.model.CancellableIncrement
import de.seuhd.campuscoffee.domain.model.GlobalActivityEntry
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

/** Reads an optional UUID field from an event body; null if absent (e.g. a kitty adjustment has no userId). */
private fun optionalUuidBody(
    event: EventEntity,
    key: String
): UUID? = event.body?.get(key)?.let { UUID.fromString(it.toString()) }

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
 * @param key the body field holding the value (`privateAmountCents`, `kittyAmountCents`, or `amountCents`)
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

/** Which money event a [WalkedRecord] came from, so each view can pick its public [ActivityEntryType]. */
private enum class WalkedKind {
    /** A coffee `+1` or an admin count override (any non-undo count change). */
    CONSUMPTION,

    /** A member's own undo of a recent coffee within the grace period. */
    CONSUMPTION_CANCEL,

    /** A bean purchase; the member feed reads its private portion, the kitty feed its kitty portion. */
    EXPENSE,

    /** A member paid money in (credits the member and feeds the kitty). */
    DEPOSIT,

    /** A pure admin kitty adjustment (no member). */
    KITTY_ADJUSTMENT,

    /** The global price was changed (no balance effect; a display row only). */
    PRICE_CHANGE
}

/**
 * One event interpreted by [ActivityWalk]: the subject member it concerns and its signed effect on that
 * member's balance and/or the kitty, with both running balances after, plus the metadata each view needs. A
 * field is null where the event does not touch it (no member effect, no kitty effect, no count, and so on).
 * Each view maps a record to its public shape and assigns the [ActivityEntryType] from [kind].
 */
private data class WalkedRecord(
    val event: EventEntity,
    val kind: WalkedKind,
    val subjectUserId: UUID?,
    val memberEffect: Long?,
    val memberBalance: Long?,
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
 * The single shared walk over a `seq`-ordered event stream that backs all three activity views. It maintains
 * per-subject balances and increment-price LIFO stacks and the one kitty balance, valuing each coffee at the
 * price in effect at its append position ([priceAsOf] over the precomputed [prices] timeline, never the inline
 * price events). [accept] folds one event and returns its interpreted [WalkedRecord], or null when the event
 * changes nothing (a zero-delta consumption). It is fed a bounded per-member stream for the member feed, the
 * kitty stream for the kitty history, and the whole log for the global feed; keying every accumulator per
 * subject makes those views agree event-for-event with the previous per-member and kitty walks even when
 * members interleave.
 *
 * @param prices the price timeline, ascending by append position
 * @param subjectLogin the login of a subject id, to tell a member's own coffee (credited at the original price
 *   on undo) from an admin override; null for an unknown or hard-deleted subject (treated as never-owner) or
 *   where ownership is irrelevant (the kitty walk, which carries no consumptions)
 */
@Suppress("UndocumentedFunction", "UndocumentedFunctionParameter")
private class ActivityWalk(
    private val prices: List<PricePoint>,
    private val subjectLogin: (UUID) -> String?
) {
    private val memberBalances = HashMap<UUID, Long>()
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
                    // cannot credit a stale price and a member cannot undo an admin-added cup
                    val price = priceAsOf(seq, prices)
                    if (delta < 0) {
                        repeat(minOf(-delta, stack.size)) { stack.removeLast() }
                    }
                    WalkedKind.CONSUMPTION to -delta.toLong() * price
                }
            }
        val balance = (memberBalances[subject] ?: 0L) + effect
        memberBalances[subject] = balance
        return WalkedRecord(
            event = event,
            kind = kind,
            subjectUserId = subject,
            memberEffect = effect,
            memberBalance = balance,
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
        val memberBalanceAfter =
            if (privateEffect != 0) {
                (memberBalances[subject] ?: 0L).plus(privateEffect).also { memberBalances[subject] = it }
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
            memberEffect = if (privateEffect != 0) privateEffect.toLong() else null,
            memberBalance = memberBalanceAfter,
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
            // a deposit credits the member and feeds the kitty by the same amount
            val memberBalanceAfter =
                (memberBalances[subject] ?: 0L).plus(amountDelta).also {
                    memberBalances[subject] =
                        it
                }
            kittyBalance += amountDelta
            WalkedRecord(
                event = event,
                kind = WalkedKind.DEPOSIT,
                subjectUserId = subject,
                memberEffect = amountDelta,
                memberBalance = memberBalanceAfter,
                kittyEffect = amountDelta,
                kittyBalance = kittyBalance
            )
        } else {
            kittyBalance += amountDelta
            WalkedRecord(
                event = event,
                kind = WalkedKind.KITTY_ADJUSTMENT,
                subjectUserId = null,
                memberEffect = null,
                memberBalance = null,
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
            memberEffect = null,
            memberBalance = null,
            kittyEffect = null,
            kittyBalance = null,
            priceAmountCents = intBody(event, "amountCents")
        )
}

/**
 * Maps a walked record to a member-feed entry (the member's running balance). Only reached for records with a
 * member effect on a member's own stream, so the kitty-only kinds never occur.
 */
private fun WalkedRecord.toMemberEntry(): ActivityEntry =
    ActivityEntry(
        type =
            when (kind) {
                WalkedKind.CONSUMPTION -> ActivityEntryType.CONSUMPTION
                WalkedKind.CONSUMPTION_CANCEL -> ActivityEntryType.CONSUMPTION_CANCEL
                WalkedKind.EXPENSE -> ActivityEntryType.PRIVATE_EXPENSE
                WalkedKind.DEPOSIT -> ActivityEntryType.DEPOSIT
                WalkedKind.KITTY_ADJUSTMENT, WalkedKind.PRICE_CHANGE ->
                    error("A member activity feed never carries a $kind record.")
            },
        id = idOf(event),
        createdAt = createdAtOf(event),
        createdBy = actorOf(event),
        note = event.note,
        amountCents = requireNotNull(memberEffect),
        runningBalanceCents = requireNotNull(memberBalance),
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
private fun WalkedRecord.toKittyEntry(): ActivityEntry =
    ActivityEntry(
        type =
            when (kind) {
                WalkedKind.DEPOSIT -> ActivityEntryType.DEPOSIT
                WalkedKind.KITTY_ADJUSTMENT -> ActivityEntryType.KITTY_ADJUSTMENT
                WalkedKind.EXPENSE -> ActivityEntryType.KITTY_EXPENSE
                WalkedKind.CONSUMPTION, WalkedKind.CONSUMPTION_CANCEL, WalkedKind.PRICE_CHANGE ->
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
 * `PRIVATE_EXPENSE` showing whichever of the member and kitty effects it moved. The subject login is stamped
 * from [subjectLoginById]; the display name is left for the domain to resolve.
 *
 * @param subjectLoginById the login of each known member by id (a subject absent from it is hard-deleted)
 */
private fun WalkedRecord.toGlobalEntry(subjectLoginById: Map<UUID, String>): GlobalActivityEntry =
    GlobalActivityEntry(
        type =
            when (kind) {
                WalkedKind.CONSUMPTION -> ActivityEntryType.CONSUMPTION
                WalkedKind.CONSUMPTION_CANCEL -> ActivityEntryType.CONSUMPTION_CANCEL
                WalkedKind.EXPENSE -> ActivityEntryType.PRIVATE_EXPENSE
                WalkedKind.DEPOSIT -> ActivityEntryType.DEPOSIT
                WalkedKind.KITTY_ADJUSTMENT -> ActivityEntryType.KITTY_ADJUSTMENT
                WalkedKind.PRICE_CHANGE -> ActivityEntryType.PRICE_CHANGE
            },
        id = idOf(event),
        createdAt = createdAtOf(event),
        actorLogin = actorOf(event),
        subjectUserId = subjectUserId,
        subjectLogin = subjectUserId?.let { subjectLoginById[it] },
        subjectName = null,
        note = event.note,
        memberEffectCents = memberEffect,
        memberBalanceCents = memberBalance,
        kittyEffectCents = kittyEffect,
        kittyBalanceCents = kittyBalance,
        count = count,
        delta = delta,
        weightGrams = weightGrams,
        privateAmountCents = privatePortion,
        kittyAmountCents = kittyPortion,
        priceAmountCents = priceAmountCents
    )

/**
 * Reads the unified-activity and balance projections straight from the append-only event log (there is no
 * activity table). A single shared [ActivityWalk] backs all three feeds: it walks a member's bounded stream
 * for the member feed, the kitty money stream for the kitty history, and the whole log for the admin global
 * feed, valuing each coffee at [priceAsOf] its append position. Money sums use [Long]; per-event effects fit
 * [Int] before widening.
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
        val walk = ActivityWalk(loadPricePoints()) { if (it == userId) ownerLogin else null }
        return eventRepository
            .findUserActivity(userId.toString())
            .mapNotNull { walk.accept(it) }
            .filter { it.subjectUserId == userId && it.memberEffect != null }
            .map { it.toMemberEntry() }
    }

    override fun kittyHistory(): List<ActivityEntry> {
        // ownership is irrelevant here: the kitty stream carries no consumptions, so byOwner is never tested
        val walk = ActivityWalk(loadPricePoints()) { null }
        return eventRepository
            .findKittyStream()
            .mapNotNull { walk.accept(it) }
            .filter { it.kittyEffect != null }
            .map { it.toKittyEntry() }
    }

    override fun globalActivity(): List<GlobalActivityEntry> {
        // Resolve every subject's login from the log's own User events (the complete historical set, so a
        // hard-deleted member still resolves), not the current users read model. login_name is immutable, so a
        // recorded login equals the one a member's own coffee was attributed to (created_by); this keeps the
        // owner test classifying a deleted member's self-scans as CONSUMPTION/CONSUMPTION_CANCEL rather than
        // misreading them as admin overrides, and lets the row carry the member's (immutable) login.
        val subjectLoginById = loadSubjectLogins()
        val walk = ActivityWalk(loadPricePoints()) { subjectLoginById[it] }
        return eventRepository
            .findActivityStream()
            .mapNotNull { walk.accept(it) }
            // drop a no-op edit that moved nothing (e.g. an expense whose note or weight changed but neither
            // money portion did): it would be a meaningless row with both effect columns blank. A price change
            // legitimately moves no balance, so it is kept.
            .filter { it.memberEffect != null || it.kittyEffect != null || it.kind == WalkedKind.PRICE_CHANGE }
            .map { it.toGlobalEntry(subjectLoginById) }
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

    /**
     * The login of every member that ever existed, by id, read from the log's `User` events so a hard-deleted
     * member still resolves. A `User` event body carries the immutable `loginName`; a DELETE body carries only
     * the id, so it is skipped (`loginName` absent) and the map keeps the login from the create. Used by the
     * global walk for the owner test and to stamp each row's subject login.
     */
    private fun loadSubjectLogins(): Map<UUID, String> =
        eventRepository
            .findByEntityTypeOrderBySeqAsc(LoggedEntityType.USER.label)
            .mapNotNull { event ->
                val login = event.body?.get("loginName")?.toString() ?: return@mapNotNull null
                uuidBody(event, "id") to login
            }.toMap()
}
