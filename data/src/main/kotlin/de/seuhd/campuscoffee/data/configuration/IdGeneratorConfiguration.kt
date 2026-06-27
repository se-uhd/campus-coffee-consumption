package de.seuhd.campuscoffee.data.configuration

import de.seuhd.campuscoffee.data.system.IdGeneratorServiceImpl
import de.seuhd.campuscoffee.domain.ports.system.IdGeneratorService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import java.util.UUID

/**
 * Builds the [IdGeneratorService]s from the configured seeds. A numeric seed gives a deterministic
 * [IdGeneratorServiceImpl]; `random` (or a blank value) gives random UUIDs.
 *
 * There are two generators with independent seeds. The `@Primary` [entityIdGenerator] (the one every other
 * component injects) assigns the entity ids; [eventIdGenerator] assigns the event log's ids in
 * event sourcing mode. They use separate seeds so the two id sequences do not coincide and the entity ids
 * do not depend on whether event sourcing is enabled.
 */
@Configuration
class IdGeneratorConfiguration {
    /**
     * Builds the primary generator that assigns the entity ids, from the entity seed.
     *
     * @param properties the configured id seeds
     * @return the entity id generator
     */
    @Bean
    @Primary
    fun entityIdGenerator(properties: IdProperties): IdGeneratorService = generatorFor(properties.entitySeed)

    /**
     * Builds the generator that assigns the event log's ids (used when event sourcing is enabled), from the event seed.
     *
     * @param properties the configured id seeds
     * @return the event id generator
     */
    @Bean(EVENT_ID_GENERATOR)
    fun eventIdGenerator(properties: IdProperties): IdGeneratorService = generatorFor(properties.eventSeed)

    /** Returns a deterministic [IdGeneratorServiceImpl] for a numeric seed, or a random generator otherwise. */
    private fun generatorFor(seed: String): IdGeneratorService =
        if (seed.isBlank() || seed.equals("random", ignoreCase = true)) {
            IdGeneratorService { UUID.randomUUID() }
        } else {
            IdGeneratorServiceImpl(seed.trim().toLong())
        }

    companion object {
        /** Bean name of the dedicated generator for the event log's ids (the qualifier the event appender injects). */
        const val EVENT_ID_GENERATOR = "eventIdGenerator"
    }
}
