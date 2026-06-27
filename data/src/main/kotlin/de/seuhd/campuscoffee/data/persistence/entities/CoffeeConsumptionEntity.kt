package de.seuhd.campuscoffee.data.persistence.entities

import jakarta.persistence.Column
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.persistence.Version

/**
 * Database entity for a user's coffee consumption (the projected read model row). Modeled exactly like
 * CampusCoffee's review entity, with a single [user] reference standing in for the review's pos/author and
 * the running [count] standing in for the review body. There is one row per user (a named unique
 * constraint on `user_id`), and [version] backs optimistic locking so two concurrent self-scans cannot
 * silently lose an increment.
 */
@jakarta.persistence.Entity
@Table(name = "coffee_consumptions")
class CoffeeConsumptionEntity : Entity() {
    @field:ManyToOne
    @field:JoinColumn(name = "user_id", nullable = false)
    var user: UserEntity? = null

    @field:Column(name = "count")
    var count: Int? = null

    @field:Version
    @field:Column(name = "version")
    var version: Long? = 0

    companion object {
        /** Name of the one-per-user unique constraint, declared in the Flyway migration. */
        const val USER_UNIQUE_CONSTRAINT = "uq_coffee_consumptions_user"
    }
}
