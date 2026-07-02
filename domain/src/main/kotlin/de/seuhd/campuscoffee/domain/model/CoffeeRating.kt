package de.seuhd.campuscoffee.domain.model

import java.time.LocalDateTime
import java.util.UUID

/**
 * Immutable coffee-rating domain model: one vote by a [user] on a [bean], a [value] from one to five. A user
 * accumulates many votes for the same bean over time (one per cup), so this carries no per-cup identity: the
 * "one vote per cancellable window" rule is enforced in the service by the vote's [createdAt] relative to the
 * current cup's window (see the rating service). The `+1` is only the moment the user is asked to rate; the
 * vote is about the beans, not that one cup.
 */
data class CoffeeRating(
    override val id: UUID? = null,
    val createdAt: LocalDateTime? = null,
    val updatedAt: LocalDateTime? = null,
    val user: User,
    val bean: CoffeeBean,
    val value: Int
) : DomainModel<UUID>
