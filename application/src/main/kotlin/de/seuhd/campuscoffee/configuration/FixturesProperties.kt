package de.seuhd.campuscoffee.configuration

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Fixture-loading configuration, bound from `campus-coffee.fixtures.*`. Declares the typed key that
 * `FixtureStartupLoader` gates on via `@ConditionalOnProperty`.
 *
 * @property loadOnStartup when true and the database has no users yet, the fixture dataset is loaded on
 *   startup (enabled in the dev and prod profiles).
 */
@ConfigurationProperties("campus-coffee.fixtures")
data class FixturesProperties(
    val loadOnStartup: Boolean = false
)
