package de.seuhd.campuscoffee.domain.ports.data

import de.seuhd.campuscoffee.domain.model.ActivityEntry
import de.seuhd.campuscoffee.domain.model.CancellableIncrement
import de.seuhd.campuscoffee.domain.model.GlobalActivityEntry
import de.seuhd.campuscoffee.domain.model.PriceChange
import java.util.UUID

/**
 * Port for the read-side projections computed straight from the append-only event log (there is no activity
 * table), implemented by the data layer. It walks a user's event streams oldest-first, valuing each
 * coffee at the price in effect when it was consumed, to produce the unified activity with a running balance;
 * the kitty history is the same idea over the global money streams.
 */
interface ActivityDataService {
    /**
     * Returns a user's full unified activity oldest-first, each entry carrying its running balance: their
     * coffees (valued at the price in effect at the time), their own private expenses, and their
     * deposits. A coffee a user undid within the grace period is credited at the exact price of the
     * increment it reversed; an admin count override is valued as a lump at the override-time price.
     *
     * @param userId     the user's id
     * @param ownerLogin the user's login name, used to tell the user's own increments/cancels (credited
     *   at the original price) from an admin override (valued as a lump)
     * @return the activity oldest-first; the last entry's running balance is the user's current balance
     */
    fun userActivity(
        userId: UUID,
        ownerLogin: String
    ): List<ActivityEntry>

    /**
     * Returns the kitty history oldest-first, each entry carrying the running kitty balance: deposits and
     * adjustments (money in) and the kitty-funded portions of expenses (money out).
     *
     * @return the kitty history oldest-first; the last entry's running balance is the current kitty balance
     */
    fun kittyHistory(): List<ActivityEntry>

    /**
     * Returns the user's most recent un-canceled own coffee increment (the one a cancel would undo),
     * found by walking their consumption events LIFO, or null if there is none.
     *
     * @param userId     the user's id
     * @param ownerLogin the user's login name, used to consider only the user's own increments
     */
    fun lastCancellableIncrement(
        userId: UUID,
        ownerLogin: String
    ): CancellableIncrement?

    /**
     * Returns the global price history oldest-first, reconstructed from the price events in the log.
     *
     * @return the price changes oldest-first
     */
    fun priceHistory(): List<PriceChange>

    /**
     * Returns the whole-installation activity oldest-first: every coffee, expense, deposit, kitty adjustment,
     * and price change across all users, one [GlobalActivityEntry] per event, each carrying the subject
     * user's running balance and the kitty's running balance (whichever the event moved). The single shared
     * walk that backs [userActivity] and [kittyHistory] is replayed over the full log here, so the per-user
     * and kitty running balances match those feeds exactly. Each subject's login is resolved from the log's
     * own `User` events (so a hard-deleted user still classifies and labels correctly); `subjectName` is left
     * null for the domain to enrich.
     *
     * @return the global activity oldest-first
     */
    fun globalActivity(): List<GlobalActivityEntry>
}
