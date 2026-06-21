package de.seuhd.campuscoffee.domain.model

import java.time.LocalDateTime
import java.util.UUID

/**
 * Immutable coffee-price domain model: the single global price charged per cup, in euro cents
 * ([amountCents]). It is event-sourced like every other entity — there is one row in the read model, but
 * each admin price change is a full-state event in the append-only log, so the complete price history is
 * retrievable from the log and the balance can value each cup at the price in effect when it was consumed.
 *
 * The price is created once (at bootstrap) and thereafter updated in place; only an admin may change it.
 * Money is held as an integer count of cents (never a floating-point euro value) to avoid rounding error.
 */
data class CoffeePrice(
    override val id: UUID? = null,
    val createdAt: LocalDateTime? = null,
    val updatedAt: LocalDateTime? = null,
    val amountCents: Int
) : DomainModel<UUID>
