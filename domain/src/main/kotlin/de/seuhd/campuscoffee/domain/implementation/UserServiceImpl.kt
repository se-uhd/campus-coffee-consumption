package de.seuhd.campuscoffee.domain.implementation

import de.seuhd.campuscoffee.domain.exceptions.ForbiddenException
import de.seuhd.campuscoffee.domain.exceptions.MissingFieldException
import de.seuhd.campuscoffee.domain.model.objects.Role
import de.seuhd.campuscoffee.domain.model.objects.User
import de.seuhd.campuscoffee.domain.model.objects.persistedId
import de.seuhd.campuscoffee.domain.ports.CapabilityTokenGenerator
import de.seuhd.campuscoffee.domain.ports.api.CoffeeConsumptionService
import de.seuhd.campuscoffee.domain.ports.api.UserService
import de.seuhd.campuscoffee.domain.ports.data.CrudDataService
import de.seuhd.campuscoffee.domain.ports.data.PasswordHasher
import de.seuhd.campuscoffee.domain.ports.data.UserDataService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Domain implementation of [UserService]. Enforces the member-management rules: a user is created only by
 * an admin (with a freshly generated capability token and a coffee consumption at zero), a user may edit
 * only their own profile unless they are an admin, and only an admin may change a user's role or active
 * state or rotate a capability token. Passwords are hashed via the [PasswordHasher] port and never read
 * back.
 */
@Service
class UserServiceImpl(
    private val userDataService: UserDataService,
    private val passwordHasher: PasswordHasher,
    private val capabilityTokenGenerator: CapabilityTokenGenerator,
    private val coffeeConsumptionService: CoffeeConsumptionService
) : CrudServiceImpl<User, UUID>(User::class.java),
    UserService {
    override fun dataService(): CrudDataService<User, UUID> = userDataService

    /**
     * Normalizes a user's server-owned secrets before persisting, enforcing the invariant that **a
     * password exists only for an admin**: a member (USER) authenticates solely with their capability token
     * and never has a password, so any supplied password is dropped and the stored hash is cleared. An
     * admin's password hash is, in order: a freshly supplied raw password (hashed), else the hash already
     * stored for that admin (on an update that does not supply a new one). When neither is present — a
     * newly created admin, or a member being promoted (who had no stored hash) — the admin would end up
     * with no password, which fails with a [MissingFieldException]. A capability token is generated for a
     * new user without one (otherwise the existing token is kept); role and active default to USER/active
     * for a create. The acting-user policy is enforced by [create]/[update] before they call this.
     */
    override fun upsert(domainObject: User): User {
        val existing = domainObject.id?.let { userDataService.getById(it) }
        val role = domainObject.role ?: existing?.role ?: Role.USER
        val capabilityToken =
            domainObject.capabilityToken ?: existing?.capabilityToken ?: capabilityTokenGenerator.newToken()
        // only an admin has a password; a member gets none (so a member cannot mint a JWT and is limited to
        // their capability link)
        val passwordHash =
            when {
                role != Role.ADMIN -> null
                domainObject.password != null -> passwordHasher.hash(domainObject.password)
                else -> existing?.passwordHash
            }
        if (role == Role.ADMIN && passwordHash == null) {
            throw MissingFieldException(User::class.java, domainObject.id, "password")
        }
        return super.upsert(
            domainObject.copy(
                role = role,
                active = domainObject.active ?: existing?.active ?: true,
                capabilityToken = capabilityToken,
                passwordHash = passwordHash,
                password = null
            )
        )
    }

    override fun getByLoginName(loginName: String): User = userDataService.getByLoginName(loginName)

    override fun findByCapabilityToken(capabilityToken: String): User? =
        userDataService.findByCapabilityToken(capabilityToken)

    override fun getById(
        id: UUID,
        actingUser: User
    ): User = userDataService.getById(id).also { requireMayView(it, actingUser) }

    override fun getByLoginName(
        loginName: String,
        actingUser: User
    ): User = userDataService.getByLoginName(loginName).also { requireMayView(it, actingUser) }

    @Transactional
    override fun create(
        user: User,
        actingUser: User
    ): User {
        requireAdmin(actingUser, "create users")
        // a fresh member: drop any client id, default the role, and let upsert assign the capability token
        val created = upsert(user.copy(id = null, role = user.role ?: Role.USER, active = user.active ?: true))
        // the consumption is created after the user so its user_id FK resolves
        coffeeConsumptionService.createForUser(created)
        return created
    }

    @Transactional
    override fun update(
        user: User,
        actingUser: User
    ): User {
        val targetId = requireNotNull(user.id) { "A user update must carry the target id." }
        val existing = userDataService.getById(targetId)
        val isAdmin = actingUser.role == Role.ADMIN
        val isSelf = actingUser.persistedId == targetId
        if (!isSelf && !isAdmin) {
            throw ForbiddenException("A user may update only their own account unless they are an admin.")
        }
        // only an admin may change the role or the active flag; a non-admin update keeps the stored values
        return upsert(
            user.copy(
                role = if (isAdmin) user.role ?: existing.role else existing.role,
                active = if (isAdmin) user.active ?: existing.active else existing.active
            )
        )
    }

    @Transactional
    override fun rotateCapabilityToken(
        userId: UUID,
        actingUser: User
    ): User {
        requireAdmin(actingUser, "rotate a capability token")
        val existing = userDataService.getById(userId)
        return upsert(existing.copy(capabilityToken = capabilityTokenGenerator.newToken()))
    }

    /** Allows a read only when [actingUser] is the target user or an admin. */
    private fun requireMayView(
        target: User,
        actingUser: User
    ) {
        if (actingUser.role != Role.ADMIN && actingUser.persistedId != target.persistedId) {
            throw ForbiddenException("User data may be read only by the user themselves or an admin.")
        }
    }

    /** Requires [actingUser] to be an admin for the given [action], else 403. */
    private fun requireAdmin(
        actingUser: User,
        action: String
    ) {
        if (actingUser.role != Role.ADMIN) {
            throw ForbiddenException("Only an admin may $action.")
        }
    }
}
