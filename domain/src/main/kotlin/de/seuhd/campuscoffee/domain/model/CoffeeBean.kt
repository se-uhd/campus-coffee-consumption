package de.seuhd.campuscoffee.domain.model

import java.time.LocalDateTime
import java.util.UUID

/**
 * Immutable coffee-bean domain model: a named coffee the group buys and rates. A bean's identity is its
 * [name]; names come from recorded bean purchases (a `BEANS` [Expense] links a bean) and from the bean
 * dropdown when a user rates.
 *
 * An admin can tidy the catalog by renaming a bean or merging one into another. A merged bean is a tombstone
 * kept for history: [active] is false and [mergedIntoId] points to the canonical bean it was merged into, so
 * its ratings and expenses resolve through to the target. A canonical (live) bean has [active] true and a
 * null [mergedIntoId]. Names are unique among canonical beans (case-insensitively).
 */
data class CoffeeBean(
    override val id: UUID? = null,
    val createdAt: LocalDateTime? = null,
    val updatedAt: LocalDateTime? = null,
    val name: String,
    val active: Boolean = true,
    val mergedIntoId: UUID? = null
) : DomainModel<UUID>
