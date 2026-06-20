package de.seuhd.campuscoffee.data.configuration

import de.seuhd.campuscoffee.domain.ports.IdGenerator
import java.util.Random
import java.util.UUID

/**
 * Generates UUIDs from a fixed seed, so the same seed always produces the same sequence of UUIDs. [reset]
 * starts the sequence over. [newId] and [reset] are `@Synchronized`, so they are mutually exclusive on this
 * instance: a [reset] running concurrently with id generation (e.g. the dev `PUT /api/dev/data` reload
 * while a write is in flight) cannot leave a caller drawing from a half-replaced generator, and the two
 * draws that make up one UUID stay together, so the deterministic sequence holds even under concurrency.
 * [java.util.Random] produces the same sequence on every platform.
 */
class SeededUuidGenerator(
    private val seed: Long
) : IdGenerator {
    private var random = Random(seed)

    @Synchronized
    override fun newId(): UUID = UUID(random.nextLong(), random.nextLong())

    @Synchronized
    override fun reset() {
        random = Random(seed)
    }
}
