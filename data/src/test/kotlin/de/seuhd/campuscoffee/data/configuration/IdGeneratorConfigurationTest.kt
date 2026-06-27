package de.seuhd.campuscoffee.data.configuration

import de.seuhd.campuscoffee.data.system.IdGeneratorServiceImpl
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Tests [IdGeneratorConfiguration] and the [IdGeneratorServiceImpl] it builds: a numeric
 * `campus-coffee.id.entity-seed` produces the same id sequence every time, and `random` (or a blank value)
 * produces a different id on each call.
 */
class IdGeneratorConfigurationTest {
    private val configuration = IdGeneratorConfiguration()

    private fun entityGenerator(seed: String) = configuration.entityIdGenerator(IdProperties(entitySeed = seed))

    private fun eventGenerator(eventSeed: String) = configuration.eventIdGenerator(IdProperties(eventSeed = eventSeed))

    @Test
    fun `a blank or random seed produces a different id on each call`() {
        for (seed in listOf("random", "RANDOM", " ")) {
            val idGenerator = entityGenerator(seed)
            assertThat(idGenerator.newId()).isNotEqualTo(idGenerator.newId())
        }
    }

    @Test
    fun `the same numeric seed produces the same id sequence on two generators`() {
        val first = entityGenerator("42")
        val second = entityGenerator("42")

        repeat(5) { assertThat(first.newId()).isEqualTo(second.newId()) }
    }

    @Test
    fun `two different seeds produce different first ids`() {
        assertThat(IdGeneratorServiceImpl(1L).newId()).isNotEqualTo(IdGeneratorServiceImpl(2L).newId())
    }

    @Test
    fun `the default entity and event seeds produce different id sequences`() {
        // the entity and event generators use separate seeds (42 and 100 by default), so an event id never
        // coincides with an entity id
        val entityIds = entityGenerator("42").let { gen -> List(3) { gen.newId() } }
        val eventIds = eventGenerator("100").let { gen -> List(3) { gen.newId() } }

        assertThat(eventIds).doesNotContainAnyElementsOf(entityIds)
    }

    @Test
    fun `seed 42 produces the ids documented for the seeded fixture users`() {
        // the fixture users are loaded in a fixed order (jane_doe first, student2023 third), so the
        // seeded generator assigns them these documented ids; a change here means the docs must be updated
        val idGenerator = IdGeneratorServiceImpl(42L)
        val firstIds = List(5) { idGenerator.newId() }

        assertThat(firstIds[0]).isEqualTo(JANE_DOE_ID)
        assertThat(firstIds[2]).isEqualTo(STUDENT2023_ID)
    }

    private companion object {
        // the ids IdGeneratorServiceImpl(42) assigns to the first and third fixture users (jane_doe and
        // student2023), also referenced by the docs
        val JANE_DOE_ID: UUID = UUID.fromString("ba419d35-0dfe-8af7-aee7-bbe10c45c028")
        val STUDENT2023_ID: UUID = UUID.fromString("aa616abe-1761-0c9a-e743-67bd738597dc")
    }

    @Test
    fun `reset restarts a seeded generator's sequence and leaves a random one random`() {
        val seeded = entityGenerator("42")
        val firstTwo = listOf(seeded.newId(), seeded.newId())
        seeded.reset()
        assertThat(listOf(seeded.newId(), seeded.newId())).isEqualTo(firstTwo)

        val random = entityGenerator("random")
        random.reset()
        assertThat(random.newId()).isNotEqualTo(random.newId())
    }
}
