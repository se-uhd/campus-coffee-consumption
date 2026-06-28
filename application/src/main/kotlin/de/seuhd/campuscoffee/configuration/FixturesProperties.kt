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
 * @property demoDataOnStartup when true (the default, dev only), `DevDemoDataLoader` layers the extra demo
 *   users and their consumption/purchase/deposit history on top of the five-user fixture set. Set false to
 *   keep only the fixtures, which the e2e does for a faster, leaner startup (the demo data is reset away by
 *   the suite anyway).
 */
@ConfigurationProperties("campus-coffee.fixtures")
data class FixturesProperties(
    val loadOnStartup: Boolean = false,
    val resetOnStartup: Boolean = false,
    val demoDataOnStartup: Boolean = true
)
