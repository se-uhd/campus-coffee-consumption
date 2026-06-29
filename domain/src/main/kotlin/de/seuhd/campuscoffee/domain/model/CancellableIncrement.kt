package de.seuhd.campuscoffee.domain.model

import java.time.LocalDateTime

/**
 * The user's most recent un-canceled own coffee increment, the one a cancel would undo, found by walking
 * their consumption events LIFO. [createdAt] is when that increment was recorded (the service compares it
 * against the grace cutoff) and [priceCents] is the price it was charged at (a cancel credits exactly this,
 * so undoing nets to zero). A read-only projection; it carries no persistence identifier (the event-store
 * append position stays in the data layer).
 */
data class CancellableIncrement(
    val createdAt: LocalDateTime,
    val priceCents: Int
)
