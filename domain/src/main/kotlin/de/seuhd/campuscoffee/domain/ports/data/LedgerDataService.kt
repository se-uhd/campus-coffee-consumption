package de.seuhd.campuscoffee.domain.ports.data

import de.seuhd.campuscoffee.domain.model.CancellableIncrement
import de.seuhd.campuscoffee.domain.model.LedgerEntry
import de.seuhd.campuscoffee.domain.model.PriceChange
import java.util.UUID

/**
 * Port for the read-side projections computed straight from the append-only event log (there is no ledger
 * table), implemented by the data layer. It walks a member's event streams oldest-first, valuing each
 * coffee at the price in effect when it was consumed, to produce the unified ledger with a running balance;
 * the kitty ledger is the same idea over the global money streams.
 */
interface LedgerDataService {
    /**
     * Returns a member's full unified ledger oldest-first, each entry carrying its running balance: their
     * coffees (valued at the price in effect at the time), their own private expenses, and their
     * settlements. A coffee a member undid within the grace period is credited at the exact price of the
     * increment it reversed; an admin count override is valued as a lump at the override-time price.
     *
     * @param userId     the member's id
     * @param ownerLogin the member's login name, used to tell the member's own increments/cancels (credited
     *   at the original price) from an admin override (valued as a lump)
     * @return the ledger oldest-first; the last entry's running balance is the member's current balance
     */
    fun memberLedger(
        userId: UUID,
        ownerLogin: String
    ): List<LedgerEntry>

    /**
     * Returns the kitty ledger oldest-first, each entry carrying the running kitty balance: settlements and
     * adjustments (money in) and the kitty-funded portions of expenses (money out).
     *
     * @return the kitty ledger oldest-first; the last entry's running balance is the current kitty balance
     */
    fun kittyLedger(): List<LedgerEntry>

    /**
     * Returns the member's most recent un-cancelled own coffee increment (the one a cancel would undo),
     * found by walking their consumption events LIFO, or null if there is none.
     *
     * @param userId     the member's id
     * @param ownerLogin the member's login name, used to consider only the member's own increments
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
}
