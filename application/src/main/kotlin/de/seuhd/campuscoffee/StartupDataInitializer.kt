package de.seuhd.campuscoffee

import de.seuhd.campuscoffee.domain.ports.StartupTask
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.stereotype.Component

/**
 * Runs every registered [StartupTask] before the embedded web server starts accepting requests, so the API
 * is never served before its data is loaded. As a [SmartInitializingSingleton] this runs at the end of
 * singleton initialization, when Flyway has migrated and every bean is ready, which for a servlet app is
 * before `finishRefresh()` starts the connectors. The tasks previously triggered themselves on
 * `ApplicationReadyEvent`, which fires after the connectors already accept and left a cold-start window
 * where a request saw the empty tables.
 *
 * Spring injects every [StartupTask] bean present (each is conditional on its own property), and they run in
 * ascending [StartupTask.order]: import the existing rows into the event log, rebuild the read model from the
 * log, then load the fixtures (whose guard then sees the rebuilt users and does not load them again).
 */
@Component
class StartupDataInitializer(
    private val startupTasks: List<StartupTask>
) : SmartInitializingSingleton {
    override fun afterSingletonsInstantiated() {
        startupTasks.sortedBy { it.order }.forEach { it.run() }
    }
}
