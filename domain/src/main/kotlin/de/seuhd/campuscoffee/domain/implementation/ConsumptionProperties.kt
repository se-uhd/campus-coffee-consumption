package de.seuhd.campuscoffee.domain.implementation

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Domain configuration for the coffee-consumption rules. It lives in the domain because the rule it carries
 * is enforced there (the domain cannot depend on the api or application modules), and it gives the key a
 * typed binding so it resolves in the IDE's `application.yaml` editor.
 *
 * @property cancelGracePeriod how long after adding a coffee a member may still undo it
 *   (`POST /api/consumption/cancel`); an undo after this window is refused with 409. Bound from
 *   `campus-coffee.consumption.cancel-grace-period`, default 5 minutes (matching `application.yaml`).
 */
@ConfigurationProperties("campus-coffee.consumption")
data class ConsumptionProperties(
    val cancelGracePeriod: Duration = Duration.ofMinutes(DEFAULT_GRACE_MINUTES)
) {
    private companion object {
        private const val DEFAULT_GRACE_MINUTES = 5L
    }
}
