package de.seuhd.campuscoffee.domain.implementation

import de.seuhd.campuscoffee.domain.exceptions.ConflictException
import de.seuhd.campuscoffee.domain.exceptions.DeletionConflictException
import de.seuhd.campuscoffee.domain.exceptions.ForbiddenException
import de.seuhd.campuscoffee.domain.exceptions.MissingFieldException
import de.seuhd.campuscoffee.domain.model.Role
import de.seuhd.campuscoffee.domain.model.User
import de.seuhd.campuscoffee.domain.model.persistedId
import de.seuhd.campuscoffee.domain.ports.CapabilityTokenGenerator
import de.seuhd.campuscoffee.domain.ports.api.CoffeeConsumptionService
import de.seuhd.campuscoffee.domain.ports.api.UserService
import de.seuhd.campuscoffee.domain.ports.data.CoffeeConsumptionDataService
import de.seuhd.campuscoffee.domain.ports.data.CrudDataService
import de.seuhd.campuscoffee.domain.ports.data.ExpenseDataService
import de.seuhd.campuscoffee.domain.ports.data.PasswordHasher
import de.seuhd.campuscoffee.domain.ports.data.PaymentDataService
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
@Suppress("TooManyFunctions")
@Service
class UserServiceImpl(
    private val userDataService: UserDataService,
    private val passwordHasher: PasswordHasher,
    private val capabilityTokenGenerator: CapabilityTokenGenerator,
    private val coffeeConsumptionService: CoffeeConsumptionService,
    private val coffeeConsumptionDataService: CoffeeConsumptionDataService,
    private val expenseDataService: ExpenseDataService,
    private val paymentDataService: PaymentDataService
) : CrudServiceImpl<User, UUID>(User::class.java),
    UserService {
    override fun dataService(): CrudDataService<User, UUID> = userDataService

    /** Labels a user by id and login name so the upsert audit trail is not an opaque UUID alone. */
    override fun describe(domainObject: User): String =
        "User with id '${domainObject.id}', login name '${domainObject.loginName}'"

    /** Labels a user by id and login name for the delete log, resolving the login name from the store. */
    override fun describeId(id: UUID): String {
        val loginName = runCatching { userDataService.getById(id).loginName }.getOrNull()
        return "User with id '$id'" + loginName?.let { ", login name '$it'" }.orEmpty()
    }

    /**
     * Refuses to hard-delete a member who has any financial footprint — a non-zero coffee count, or any
     * expense or settlement — so the financial history is preserved (an admin deactivates them instead).
     * A pristine member (count zero, no money records) is deleted, cascading their zeroed consumption row.
     * Also refuses to delete the last remaining active admin, so the system never locks every admin out.
     *
     * @throws ConflictException if deleting the last remaining active admin
     * @throws DeletionConflictException if the member has financial history
     */
    @Transactional
    override fun delete(id: UUID) {
        val target = runCatching { userDataService.getById(id) }.getOrNull()
        if (target != null) {
            requireNotLastActiveAdmin(target, "delete")
        }
        val hasConsumed = coffeeConsumptionDataService.getByUserId(id).count != 0
        val hasExpenses = expenseDataService.getAllByBuyer(id).isNotEmpty()
        val hasPayments = paymentDataService.getAllByUser(id).isNotEmpty()
        if (hasConsumed || hasExpenses || hasPayments) {
            throw DeletionConflictException(User::class.java, id)
        }
        super.delete(id)
    }

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
        // a deactivated member is read-only: a self profile edit (PUT /api/profile) by an inactive,
        // still-authenticated member is rejected, matching every other member mutation. An admin may still
        // edit anyone (including themselves) so a deactivated admin is not locked out of administration.
        if (isSelf && !isAdmin && actingUser.active != true) {
            throw ForbiddenException("A deactivated member is read-only and cannot edit their profile.")
        }
        // only an admin may change the role or the active flag; a non-admin update keeps the stored values
        val newRole = if (isAdmin) user.role ?: existing.role else existing.role
        val newActive = if (isAdmin) user.active ?: existing.active else existing.active
        // refuse to demote (ADMIN -> USER) or deactivate (active -> false) the last remaining active admin
        if (newRole != Role.ADMIN || newActive == false) {
            requireNotLastActiveAdmin(existing, "demote or deactivate")
        }
        return upsert(user.copy(role = newRole, active = newActive))
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

    /**
     * Refuses the given [action] when [target] is the last remaining active admin, so the system can never
     * lose its final administrator. [target] is the last active admin when it is itself an active admin and
     * no other user is. Callers invoke this only for an operation that would remove [target]'s active-admin
     * status (a demotion, a deactivation, or a delete); for any other operation the guard is a no-op because
     * [target] is left an active admin.
     *
     * @param target the user whose active-admin status the operation would remove
     * @param action the human-readable verb for the conflict message (e.g. "delete")
     * @throws ConflictException if [target] is the last remaining active admin
     */
    private fun requireNotLastActiveAdmin(
        target: User,
        action: String
    ) {
        val isActiveAdmin = target.role == Role.ADMIN && target.active != false
        if (!isActiveAdmin) {
            return
        }
        val otherActiveAdmins =
            userDataService.getAll().any { other ->
                other.persistedId != target.persistedId && other.role == Role.ADMIN && other.active != false
            }
        if (!otherActiveAdmins) {
            throw ConflictException("At least one active admin must remain; you cannot $action the last one.")
        }
    }
}
