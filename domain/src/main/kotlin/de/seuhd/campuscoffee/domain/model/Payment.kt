package de.seuhd.campuscoffee.domain.model

import java.time.LocalDateTime
import java.util.UUID

/**
 * Immutable payment domain model: an admin-recorded movement of money into the communal kitty, in euro
 * cents ([amountCents]). The nullable [user] is the load-bearing distinction:
 * - present, a **settlement**: the member paid money in, which credits their balance and feeds the kitty;
 * - absent, a pure **kitty adjustment**: an initial float or a correction that changes only the kitty.
 *
 * Settlements are positive; a kitty adjustment may be signed (a correction can remove money). Payments are
 * never edited (a mistake is corrected with a compensating entry), so the append-only log is the full
 * record of how the kitty reached its balance. The [user] flattens to a user id in the event body.
 */
data class Payment(
    override val id: UUID? = null,
    val createdAt: LocalDateTime? = null,
    val updatedAt: LocalDateTime? = null,
    val user: User? = null,
    val amountCents: Int,
    val note: String? = null
) : DomainModel<UUID>
