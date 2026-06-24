package de.seuhd.campuscoffee.data.persistence.entities

import jakarta.persistence.Column
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.persistence.Version

/**
 * Database entity for a kitty money movement (the projected read model row). The [user] reference is the
 * member who paid in for a deposit, or null for a pure kitty adjustment. [amountCents] is signed (a
 * deposit is positive; a correcting adjustment may be negative). [version] backs optimistic locking.
 * All money is in euro cents.
 */
@jakarta.persistence.Entity
@Table(name = "payments")
class PaymentEntity : Entity() {
    @field:ManyToOne
    @field:JoinColumn(name = "user_id", nullable = true)
    var user: UserEntity? = null

    @field:Column(name = "amount_cents")
    var amountCents: Int? = null

    @field:Column(name = "note")
    var note: String? = null

    @field:Version
    @field:Column(name = "version")
    var version: Long? = 0
}
