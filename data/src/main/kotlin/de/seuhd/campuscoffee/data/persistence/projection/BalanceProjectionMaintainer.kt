package de.seuhd.campuscoffee.data.persistence.projection

import de.seuhd.campuscoffee.data.persistence.entities.EventEntity

/**
 * Keeps the balance projections (the per-user `user_balance` rows and the single `kitty_balance` row)
 * consistent with the event log. The event-sourcing write path and the read-model rebuild depend on this
 * interface rather than on the concrete projection class, so the dependency points at an abstraction; the
 * implementation is [BalanceDataServiceImpl].
 */
interface BalanceProjectionMaintainer {
    /**
     * Refreshes the balances the just-appended [event] affects, recomputing them from the log inside the same
     * write transaction (so they roll back together with the event).
     *
     * @param event the appended event whose affected balances to refresh
     */
    fun maintain(event: EventEntity)

    /** Empties both balance projections (when a type's events are cleared, or before a full rebuild). */
    fun clear()

    /** Rebuilds every balance from the whole log: clears the projections, then recomputes each user and the kitty. */
    fun rebuildAll()
}
