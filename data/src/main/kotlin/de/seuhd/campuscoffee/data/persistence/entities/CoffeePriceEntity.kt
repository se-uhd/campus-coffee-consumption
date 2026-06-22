package de.seuhd.campuscoffee.data.persistence.entities

import jakarta.persistence.Column
import jakarta.persistence.Table
import jakarta.persistence.Version

/**
 * Database entity for the global coffee price (the projected read model row). There is a single row,
 * created once and updated in place; [version] backs optimistic locking so two concurrent admin price
 * changes cannot silently lose one. The amount is held in euro cents.
 */
@jakarta.persistence.Entity
@Table(name = "coffee_prices")
class CoffeePriceEntity : Entity() {
    @field:Column(name = "amount_cents")
    var amountCents: Int? = null

    @field:Version
    @field:Column(name = "version")
    var version: Long? = null

    companion object {
        const val SINGLETON_COLUMN = "is_singleton"

        /** The single-row guard constraint, declared in the Flyway migration; the entity does not map the
         *  column (the database default supplies it). This name is the single source of truth shared by the
         *  DDL, the relational ConstraintMapping, and the event-sourcing projector's duplication rules. */
        const val SINGLETON_UNIQUE_CONSTRAINT = "uq_coffee_prices_singleton"
    }
}
