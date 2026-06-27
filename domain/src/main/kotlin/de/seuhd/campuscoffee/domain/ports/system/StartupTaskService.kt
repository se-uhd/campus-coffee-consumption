package de.seuhd.campuscoffee.domain.ports.system

/**
 * A task run once at application startup, before the web server accepts requests. The application runs every
 * registered task in ascending [order]; adapters in the other layers (the data-layer event sourcing
 * migrations and the application's fixture loader) implement it. Declaring the contract in the domain lets
 * the application orchestrate the tasks without compile-coupling to the layers that implement them.
 */
interface StartupTaskService {
    /** The position in the startup sequence; a lower value runs first. */
    val order: Int

    /** Performs the startup work. */
    fun run()
}
