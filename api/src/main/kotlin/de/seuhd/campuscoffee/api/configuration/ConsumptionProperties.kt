package de.seuhd.campuscoffee.api.configuration

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Member-consumption configuration, bound from `campus-coffee.consumption.*`. Declares the typed key for
 * the undo grace period so it resolves in the IDE's `application.yaml` editor; the domain consumption
 * service reads the same key to decide how long a member may undo a recent coffee.
 *
 * @property cancelGracePeriod how long after adding a coffee a member may still undo it (e.g. `5m`)
 */
@ConfigurationProperties("campus-coffee.consumption")
data class ConsumptionProperties(
    val cancelGracePeriod: Duration = Duration.ofMinutes(DEFAULT_GRACE_MINUTES)
) {
    private companion object {
        private const val DEFAULT_GRACE_MINUTES = 5L
    }
}
