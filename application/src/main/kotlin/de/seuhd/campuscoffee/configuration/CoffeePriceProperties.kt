package de.seuhd.campuscoffee.configuration

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Coffee-price bootstrap configuration, bound from `campus-coffee.price.*`. Used by the startup loader to
 * seed the initial price the first time the application runs against an empty log, so a price always exists
 * before any coffee is consumed.
 *
 * @property initialCents the initial price per cup in euro cents, seeded when no price exists yet
 */
@ConfigurationProperties("campus-coffee.price")
data class CoffeePriceProperties(
    val initialCents: Int = 50
)
