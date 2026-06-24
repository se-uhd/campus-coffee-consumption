package de.seuhd.campuscoffee.data.persistence.entities

import jakarta.persistence.Column
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.util.UUID

/**
 * Read-model projection of one member's running balance in euro cents (negative means they owe the fund). It
 * is derived from the event log and maintained on every write that affects the member, so the per-member
 * overview reads it directly instead of replaying each member's whole stream. The row is keyed by the member
 * id and cascades away with the member.
 */
@jakarta.persistence.Entity
@Table(name = "member_balance")
class MemberBalanceEntity {
    @field:Id
    @field:Column(name = "user_id")
    var userId: UUID? = null

    @field:Column(name = "balance_cents")
    var balanceCents: Long = 0
}
