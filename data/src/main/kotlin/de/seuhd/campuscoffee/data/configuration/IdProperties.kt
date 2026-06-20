package de.seuhd.campuscoffee.data.configuration

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Seeds for the application-assigned ids, bound from `campus-coffee.id.*`. A numeric seed makes the assigned
 * ids deterministic and reproducible; `random` (or a blank value) uses random UUIDs. The entity ids and the
 * event log's ids use separate seeds so the two id sequences do not coincide.
 *
 * @property entitySeed the seed for the entity id generator; numeric (the default) or `random`.
 * @property eventSeed the seed for the event log's id generator.
 */
@ConfigurationProperties("campus-coffee.id")
data class IdProperties(
    val entitySeed: String = "42",
    val eventSeed: String = "100"
)
