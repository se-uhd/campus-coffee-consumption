package de.seuhd.campuscoffee.domain.ports.system

import java.util.UUID

/**
 * Generates the [UUID] id for a new entity. A port in the hexagonal (ports-and-adapters) architecture,
 * declared by the domain and implemented by the data layer, so the application assigns an entity's id
 * before it is stored (rather than letting the database generate it).
 */
fun interface IdGeneratorService {
    /** Returns an id for a new entity. */
    fun newId(): UUID

    /** Starts a deterministic generator's sequence over; does nothing for a random one. */
    fun reset() {}
}
