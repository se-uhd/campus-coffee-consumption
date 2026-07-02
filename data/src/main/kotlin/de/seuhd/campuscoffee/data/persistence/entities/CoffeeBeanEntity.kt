package de.seuhd.campuscoffee.data.persistence.entities

import jakarta.persistence.Column
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.util.UUID

/**
 * Database entity for a coffee bean (the projected read model row). A bean's [name] is unique among
 * canonical beans (the partial unique index [NAME_UNIQUE_CONSTRAINT], on `lower(name)` where the bean is not
 * merged). A merged bean is a tombstone: [active] is false and [mergedIntoId] points at the canonical bean.
 * [version] backs optimistic locking. [mergedIntoId] is a plain id column (a self reference), not a mapped
 * association, so a projection reconstructs a bean field for field with no reference to resolve.
 */
@jakarta.persistence.Entity
@Table(name = "coffee_beans")
class CoffeeBeanEntity : Entity() {
    @field:Column(name = "name")
    var name: String? = null

    @field:Column(name = "active")
    var active: Boolean? = null

    @field:Column(name = "merged_into_id")
    var mergedIntoId: UUID? = null

    @field:Version
    @field:Column(name = "version")
    var version: Long? = 0

    companion object {
        /** Name of the partial unique index guarding one live bean per name, declared in the Flyway migration. */
        const val NAME_UNIQUE_CONSTRAINT = "uq_coffee_beans_active_name"

        /** The bean-name column the unique index guards. */
        const val NAME_COLUMN = "name"
    }
}
