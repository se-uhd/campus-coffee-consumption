package de.seuhd.campuscoffee.data.persistence.entities

import jakarta.persistence.Column
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.util.UUID

/**
 * Read-model projection of one user's running balance in euro cents (negative means they owe the fund). It
 * is derived from the event log and maintained on every write that affects the user, so the per-user
 * overview reads it directly instead of replaying each user's whole stream. The row is keyed by the user
 * id and cascades away with the user.
 */
@jakarta.persistence.Entity
@Table(name = "user_balance")
class UserBalanceEntity {
    @field:Id
    @field:Column(name = "user_id")
    var userId: UUID? = null

    @field:Column(name = "balance_cents")
    var balanceCents: Long = 0
}
