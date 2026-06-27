@file:Suppress("TooManyFunctions")

package de.seuhd.campuscoffee.data.persistence.projection
import de.seuhd.campuscoffee.data.persistence.entities.ChangeType
import de.seuhd.campuscoffee.data.persistence.entities.EventEntity
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.LocalDateTime
import java.util.UUID

private const val SYSTEM_ACTOR = "SYSTEM"

private val log = KotlinLogging.logger {}

/** Reads an integer field from an event body, tolerating Int/Long/BigInteger decoding; null if absent. */
internal fun intBody(
    event: EventEntity,
    key: String
): Int? = (event.body?.get(key) as? Number)?.toInt()

/**
 * The (private, kitty) portions of a split bean purchase, for the admin activity breakdown, or `(null, null)`
 * when there is no split to show: a DELETE (which only reverses a balance) or an expense with no kitty
 * portion (a fully-private purchase). The two are surfaced together so both admin views (the user activity's
 * PRIVATE_EXPENSE row and the kitty history's KITTY_EXPENSE row) render the same `private + kitty` split.
 */
internal fun splitPortions(event: EventEntity): Pair<Long?, Long?> {
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
internal fun uuidBody(
    event: EventEntity,
    key: String
): UUID = UUID.fromString(requireNotNull(event.body?.get(key)) { "An event body must carry $key." }.toString())

/** Reads an optional UUID field from an event body; null if absent (e.g. a kitty adjustment has no userId). */
internal fun optionalUuidBody(
    event: EventEntity,
    key: String
): UUID? = event.body?.get(key)?.let { UUID.fromString(it.toString()) }

/**
 * The event's append position. `seq` is assigned by the identity column at INSERT, not at commit, and price
 * changes and `+1` consumptions are not serialized by a lock, so two concurrent unlocked transactions could
 * in principle acquire `seq` in one order and commit in the other. A coffee consumed in the same instant a
 * price change commits could then be valued at the old or new price in the maintained user_balance until
 * the next write for that user recomputes it from the log and corrects it. In practice a price change does
 * not interleave with self-scans, so this window is not reached; the kitty-overdraw path that must not race
 * is separately serialized by the advisory lock.
 */
internal fun seqOf(event: EventEntity): Long = requireNotNull(event.seq) { "A stored event must carry a seq." }

/**
 * The event's own id, used as an activity entry's stable per-entry key (for client-side paging and dedup). The
 * append position [seqOf] orders the walk but stays inside the data layer; the entry exposes this id instead.
 */
internal fun idOf(event: EventEntity): UUID = requireNotNull(event.id) { "A stored event must carry an id." }

/** The event's recorded time. */
internal fun createdAtOf(event: EventEntity): LocalDateTime =
    requireNotNull(event.createdAt) { "A stored event must carry a createdAt." }

/** The event's actor login, defaulting to the system actor. */
internal fun actorOf(event: EventEntity): String = event.createdBy ?: SYSTEM_ACTOR

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
internal fun deltaEffect(
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
 * price (never 0) rather than throwing, so one malformed user stream cannot 500 a whole admin overview or
 * activity read. Only an empty price history falls back to 0, and that case is logged at warn level so a
 * coffee charged nothing is not silent.
 */
internal fun priceAsOf(
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
