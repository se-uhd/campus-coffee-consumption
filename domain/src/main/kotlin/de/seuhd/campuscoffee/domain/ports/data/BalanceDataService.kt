package de.seuhd.campuscoffee.domain.ports.data

import java.util.UUID

/**
 * Read access to the maintained balance projections: a member's running balance and the global kitty
 * balance, both in euro cents. These are derived from the event log but kept materialized so the hot reads
 * (the per-member overview and the kitty-overdraw guard) do not replay a whole stream on every call. A
 * member with no recorded activity yet has a zero balance.
 */
interface BalanceDataService {
    /**
     * The member's current balance in cents (negative means they owe the fund), or 0 if none is recorded.
     *
     * @param userId the member whose balance to read
     */
    fun memberBalanceCents(userId: UUID): Long

    /** Every member's current balance in cents, keyed by member id (a member absent from the map is 0). */
    fun allMemberBalancesCents(): Map<UUID, Long>

    /** The current global kitty balance in cents (never negative by the overdraw guard). */
    fun kittyBalanceCents(): Long
}
