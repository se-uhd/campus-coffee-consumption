package de.seuhd.campuscoffee

import de.seuhd.campuscoffee.configuration.BootstrapAdminProperties
import de.seuhd.campuscoffee.domain.model.Role
import de.seuhd.campuscoffee.domain.model.User
import de.seuhd.campuscoffee.domain.ports.StartupTask
import de.seuhd.campuscoffee.domain.ports.api.CoffeeConsumptionService
import de.seuhd.campuscoffee.domain.ports.api.UserService
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component

/**
 * Creates a single bootstrap admin on startup when [BootstrapAdminProperties] supplies credentials and no
 * admin exists yet, so a fresh prod deployment (where fixtures are off) is reachable. It runs after the
 * fixture loader, so in dev (where the fixtures already seed an admin) it sees that admin and does
 * nothing. The created admin gets a generated capability token and a coffee consumption at zero, exactly
 * like a member created through the API.
 *
 * [StartupDataInitializer] runs it before the web server accepts requests.
 */
@Component
class BootstrapAdminLoader(
    private val userService: UserService,
    private val coffeeConsumptionService: CoffeeConsumptionService,
    private val bootstrapAdminProperties: BootstrapAdminProperties
) : StartupTask {
    override val order = ORDER

    override fun run() = createBootstrapAdmin()

    /** Creates the bootstrap admin unless its credentials are absent or an admin already exists. */
    fun createBootstrapAdmin() {
        val loginName = bootstrapAdminProperties.loginName
        val password = bootstrapAdminProperties.password
        if (loginName.isNullOrBlank() || password.isNullOrBlank()) {
            log.info { "Skipping the bootstrap admin: no bootstrap-admin credentials configured." }
            return
        }
        if (userService.getAll().any { it.role == Role.ADMIN }) {
            log.info { "Skipping the bootstrap admin: an admin already exists." }
            return
        }
        val created =
            userService.upsert(
                User(
                    loginName = loginName,
                    emailAddress = bootstrapAdminProperties.emailAddress,
                    firstName = bootstrapAdminProperties.firstName,
                    lastName = bootstrapAdminProperties.lastName,
                    role = Role.ADMIN,
                    active = true,
                    password = password
                )
            )
        coffeeConsumptionService.createForUser(created)
        log.info { "Created the bootstrap admin '$loginName'." }
    }

    private companion object {
        // runs after the fixture loader (200), so a seeded admin in dev makes this a no-op
        private const val ORDER = 300
        private val log = KotlinLogging.logger {}
    }
}
