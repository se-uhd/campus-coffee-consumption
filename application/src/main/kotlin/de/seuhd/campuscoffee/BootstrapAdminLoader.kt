package de.seuhd.campuscoffee

import de.seuhd.campuscoffee.api.dtos.MIN_PASSWORD_LENGTH
import de.seuhd.campuscoffee.api.dtos.PASSWORD_COMPLEXITY_PATTERN
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
        with(bootstrapAdminProperties) {
            if (loginName.isNullOrBlank() && password.isNullOrBlank()) {
                log.info { "Skipping the bootstrap admin: no bootstrap-admin credentials configured." }
                return
            }
            if (userService.getAll().any { it.role == Role.ADMIN }) {
                log.info { "Skipping the bootstrap admin: an admin already exists." }
                return
            }
            // at least one credential is set, so require the whole profile (non-blank and long enough): a
            // partial or blank configuration fails fast instead of creating an admin with empty credentials
            val created =
                userService.upsert(
                    User(
                        loginName = loginName.required("login-name", MIN_LOGIN_NAME_LENGTH),
                        emailAddress = emailAddress.required("email-address"),
                        firstName = firstName.required("first-name"),
                        lastName = lastName.required("last-name"),
                        role = Role.ADMIN,
                        active = true,
                        password = password.requiredPassword()
                    )
                )
            coffeeConsumptionService.createForUser(created)
            log.info { "Created the bootstrap admin with id '${created.id}'." }
        }
    }

    /**
     * Returns this value when it is set, non-blank, and at least [minLength] characters; otherwise fails
     * startup with a message naming the offending `campus-coffee.bootstrap-admin` key.
     *
     * @param key       the property key, for the error message
     * @param minLength the minimum number of characters the value must have
     */
    private fun String?.required(
        key: String,
        minLength: Int = 1
    ): String {
        require(!isNullOrBlank() && length >= minLength) {
            "campus-coffee.bootstrap-admin.$key must be set, non-blank, and at least $minLength character(s) " +
                "when the bootstrap admin is configured."
        }
        return this
    }

    /**
     * Validates the bootstrap-admin password against the same policy the API enforces on the admin DTO
     * (at least [MIN_PASSWORD_LENGTH] characters, with a lowercase letter, an uppercase letter, and a digit),
     * since the bootstrap path bypasses the DTO. Fails startup with a message naming the offending key.
     */
    private fun String?.requiredPassword(): String {
        val value = required("password", MIN_PASSWORD_LENGTH)
        require(PASSWORD_COMPLEXITY_REGEX.matches(value)) {
            "campus-coffee.bootstrap-admin.password must contain at least one lowercase letter, one uppercase " +
                "letter, and one digit."
        }
        return value
    }

    private companion object {
        private const val ORDER = 300
        private const val MIN_LOGIN_NAME_LENGTH = 3
        private val PASSWORD_COMPLEXITY_REGEX = Regex(PASSWORD_COMPLEXITY_PATTERN)
        private val log = KotlinLogging.logger {}
    }
}
