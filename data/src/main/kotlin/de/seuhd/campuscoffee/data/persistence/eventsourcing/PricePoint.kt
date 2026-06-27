package de.seuhd.campuscoffee.data.persistence.eventsourcing

/** A point on the price timeline: the price that took effect at append position [seq]. */
internal data class PricePoint(
    val seq: Long,
    val amountCents: Int
)
