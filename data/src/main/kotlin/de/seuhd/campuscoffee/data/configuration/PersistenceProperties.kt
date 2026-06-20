package de.seuhd.campuscoffee.data.configuration

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Persistence configuration, bound from `campus-coffee.persistence.*`. Event sourcing is the only
 * persistence mode (the CampusCoffee relational / event sourcing toggle was dropped), so the only key left
 * is the opt-in rebuild flag below; this class is the `@ConfigurationProperties` home that lets the IDE
 * resolve that key in `application.yaml`, mirroring the other `*Properties` classes.
 *
 * @property eventsToDataOnStartup when true, rebuild the relational read tables from the event log on
 *   startup (clear the tables and replay the whole log in append order) — an event sourcing demonstration.
 *   `EventsToDataRunner` activates on this key via `@ConditionalOnProperty`. Off by default.
 */
@ConfigurationProperties("campus-coffee.persistence")
data class PersistenceProperties(
    val eventsToDataOnStartup: Boolean = false
)
