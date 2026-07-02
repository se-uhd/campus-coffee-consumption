package de.seuhd.campuscoffee.data.persistence.entities

import de.seuhd.campuscoffee.domain.model.ExpenseType
import jakarta.persistence.Column
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.persistence.Version

/**
 * Database entity for a recorded group outlay (the projected read model row). It references the [buyer]
 * (the `buyer_user_id` foreign key), carries its [expenseType] and, for a bean purchase, the [bean]
 * (`bean_id`) and [weightGrams] (both null for a non-bean outlay), plus the total [amountCents] and the
 * [privateAmountCents]/[kittyAmountCents] split (which always sum to the total, a database CHECK backs the
 * domain-service validation). [version] backs optimistic locking. All money is in euro cents.
 */
@jakarta.persistence.Entity
@Table(name = "expenses")
class ExpenseEntity : Entity() {
    @field:ManyToOne
    @field:JoinColumn(name = "buyer_user_id", nullable = false)
    var buyer: UserEntity? = null

    @field:Enumerated(EnumType.STRING)
    @field:Column(name = "expense_type")
    var expenseType: ExpenseType? = null

    @field:ManyToOne
    @field:JoinColumn(name = "bean_id")
    var bean: CoffeeBeanEntity? = null

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
    var version: Long? = 0
}
