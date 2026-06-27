package de.seuhd.campuscoffee.domain.model

import java.time.LocalDateTime

/**
 * A single recorded change to a user's coffee count, reconstructed from one event-log row rather than
 * from a dedicated table. Named a "change" rather than a "transaction" to avoid the ACID/database
 * connotation, and distinct from the generic event sourcing "event" so the two are not confused. Unlike
 * the stored domain models it carries no identifier: it is a read-only projection of the log.
 *
 * [count] is the running total recorded by that change, [delta] the difference from the previous change
 * (`+1` or `-1` for a self-scan, the whole count for the initial insert, or the signed jump for an admin
 * override), [createdAt] when the change was recorded, [createdBy] the login name of whoever made it (the
 * user, an admin, or `"SYSTEM"` for the seeded data and bootstrap), and [note] an optional admin
 * annotation documenting an override (e.g. the reason for correcting a miscount).
 */
data class ConsumptionChange(
    val count: Int,
    val delta: Int,
    val createdAt: LocalDateTime,
    val createdBy: String,
    val note: String? = null
)
