package de.seuhd.campuscoffee.data.persistence.entities

import jakarta.persistence.Column
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.persistence.Version

/**
 * Database entity for a coffee rating (the projected read model row): one vote by [user] on [bean] with a
 * [value] from one to five. There is no unique constraint (votes accumulate, one per cup window), so the
 * one-vote-per-window rule is enforced in the service; [version] backs optimistic locking.
 */
@jakarta.persistence.Entity
@Table(name = "coffee_ratings")
class CoffeeRatingEntity : Entity() {
    @field:ManyToOne
    @field:JoinColumn(name = "user_id", nullable = false)
    var user: UserEntity? = null

    @field:ManyToOne
    @field:JoinColumn(name = "bean_id", nullable = false)
    var bean: CoffeeBeanEntity? = null

    @field:Column(name = "value")
    var value: Int? = null

    @field:Version
    @field:Column(name = "version")
    var version: Long? = 0
}
