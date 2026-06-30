package de.seuhd.campuscoffee.domain.implementation

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.ZoneId

/**
 * Domain configuration for the landing summary's cup-stat windows. It lives in the domain because the windows
 * are computed there (the domain cannot depend on the api or application modules), and it gives the key a
 * typed binding so it resolves in the IDE's `application.yaml` editor.
 *
 * @property timeZone the time zone whose calendar defines the cup-stat windows on the landing's `CUPS` panel:
 *   "today" is since local midnight and "this week" since the most recent Monday 00:00 in this zone (the
 *   activity timestamps are UTC and are compared against these boundaries). Bound from
 *   `campus-coffee.summary.time-zone`, default `Europe/Berlin` (matching `application.yaml`).
 */
@ConfigurationProperties("campus-coffee.summary")
data class SummaryProperties(
    val timeZone: ZoneId = ZoneId.of("Europe/Berlin")
)
