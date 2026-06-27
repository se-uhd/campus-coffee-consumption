package de.seuhd.campuscoffee.domain.ports.data

import java.util.UUID

/**
 * Read access to the maintained balance projections: a user's running balance and the global kitty
 * balance, both in euro cents. These are derived from the event log but kept materialized so the hot reads
 * (the per-user overview and the kitty-overdraw guard) do not replay a whole stream on every call. A
 * user with no recorded activity yet has a zero balance.
 */
interface BalanceDataService {
    /**
     * The user's current balance in cents (negative means they owe the fund), or 0 if none is recorded.
     *
     * @param userId the user whose balance to read
     */
    fun userBalanceCents(userId: UUID): Long

    /** Every user's current balance in cents, keyed by user id (a user absent from the map is 0). */
    fun allUserBalancesCents(): Map<UUID, Long>

    /** The current global kitty balance in cents (never negative by the overdraw guard). */
    fun kittyBalanceCents(): Long
}
