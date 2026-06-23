package de.seuhd.campuscoffee.data.persistence.entities

import jakarta.persistence.Column
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.persistence.Version

/**
 * Database entity for a recorded bean purchase (the projected read model row). It references the [buyer]
 * (the `buyer_user_id` foreign key), carries the bean [weightGrams] and total [amountCents], and the
 * [privateAmountCents]/[kittyAmountCents] split (which always sum to the total, a database CHECK backs the
 * domain-service validation). [version] backs optimistic locking. All money is in euro cents.
 */
@jakarta.persistence.Entity
@Table(name = "expenses")
class ExpenseEntity : Entity() {
    @field:ManyToOne
    @field:JoinColumn(name = "buyer_user_id", nullable = false)
    var buyer: UserEntity? = null

    @field:Column(name = "weight_grams")
    var weightGrams: Int? = null

    @field:Column(name = "amount_cents")
    var amountCents: Int? = null

    @field:Column(name = "private_amount_cents")
    var privateAmountCents: Int? = null

    @field:Column(name = "kitty_amount_cents")
    var kittyAmountCents: Int? = null

    @field:Column(name = "note")
    var note: String? = null

    @field:Version
    @field:Column(name = "version")
    var version: Long? = null
}
