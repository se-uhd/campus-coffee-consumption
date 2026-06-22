package de.seuhd.campuscoffee.configuration

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Fixture-loading configuration, bound from `campus-coffee.fixtures.*`. Declares the typed key that
 * `FixtureStartupLoader` gates on via `@ConditionalOnProperty`.
 *
 * @property loadOnStartup when true and the database has no users yet, the fixture dataset is loaded on
 *   startup (enabled in the dev and prod profiles).
 * @property resetOnStartup when true (dev only), every startup first clears all data and reseeds the
 *   fixtures with their deterministic ids. The in-memory seeded id generators restart their sequence on
 *   every boot, so without this a persisted dev database keeps the previous run's rows and the next assigned
 *   id collides with one of them; reseeding from a cleared database keeps the ids and the data in step.
 */
@ConfigurationProperties("campus-coffee.fixtures")
data class FixturesProperties(
    val loadOnStartup: Boolean = false,
    val resetOnStartup: Boolean = false
)
