package de.seuhd.campuscoffee.domain.ports.api

import de.seuhd.campuscoffee.domain.exceptions.ForbiddenException
import de.seuhd.campuscoffee.domain.exceptions.NotFoundException
import de.seuhd.campuscoffee.domain.model.User
import de.seuhd.campuscoffee.domain.ports.data.UserDataService
import java.util.UUID

/**
 * Service interface for user operations.
 *
 * This is a port in the hexagonal architecture pattern, implemented by the domain layer and consumed by
 * the API layer. It encapsulates business rules and orchestrates data operations through the
 * [UserDataService] port.
 *
 * Extends [CrudService] to inherit common CRUD operations and adds user-specific operations. Unlike
 * CampusCoffee there is no open self-registration: creating a user is an admin operation ([create]), and
 * the capability token a member authenticates with is issued by the service, never by the client.
 */
interface UserService : CrudService<User, UUID> {
    /**
     * Retrieves a specific user by their unique login name. This overload resolves the authenticated
     * principal itself (turning a login name into a [User]) and is therefore not subject to the
     * self-or-admin read rule; use [getByLoginName] with an `actingUser` for client-facing lookups.
     *
     * @param loginName the unique login name of the user to retrieve
     * @return the user with the specified login name
     * @throws NotFoundException if no user exists with the given login name
     */
    fun getByLoginName(loginName: String): User

    /**
     * Resolves a member by their secret capability token, used by the capability token authentication
     * filter to turn an `X-Coffee-Token` header into a principal. Returns null (rather than throwing) for
     * an unknown or rotated token, so the filter can answer 401 without surfacing an error.
     *
     * @param capabilityToken the secret capability token to resolve
     * @return the owning user, or null if no active token matches
     */
    fun findByCapabilityToken(capabilityToken: String): User?

    /**
     * Retrieves a user by id on behalf of [actingUser]. User data is not public, so only the target user
     * themselves or an admin may read it.
     *
     * @param id         the id of the user to retrieve
     * @param actingUser the authenticated user attempting the read
     * @throws NotFoundException if no user exists with [id]
     * @throws ForbiddenException if [actingUser] is neither the target user nor an admin
     */
    fun getById(
        id: UUID,
        actingUser: User
    ): User

    /**
     * Retrieves a user by login name on behalf of [actingUser], with the same self-or-admin rule as the
     * id-based [getById].
     *
     * @param loginName  the login name of the user to retrieve
     * @param actingUser the authenticated user attempting the read
     * @throws NotFoundException if no user exists with [loginName]
     * @throws ForbiddenException if [actingUser] is neither the target user nor an admin
     */
    fun getByLoginName(
        loginName: String,
        actingUser: User
    ): User

    /**
     * Creates a new user on behalf of an admin [actingUser]. The service assigns a fresh, unguessable
     * capability token, honors the requested [User.role], and creates the user's
     * [de.seuhd.campuscoffee.domain.model.CoffeeConsumption] at `count = 0`. A password applies
     * only to an admin: creating an `ADMIN` requires one (it is hashed), while a member (`USER`) gets none
     * and authenticates solely with their capability token (any password sent for a member is ignored).
     *
     * @param user       the user to create
     * @param actingUser the authenticated user attempting the create
     * @return the persisted user, including the assigned capability token
     * @throws ForbiddenException if [actingUser] is not an admin
     * @throws de.seuhd.campuscoffee.domain.exceptions.MissingFieldException if creating an admin without a password
     */
    fun create(
        user: User,
        actingUser: User
    ): User

    /**
     * Updates a user on behalf of [actingUser], enforcing the self-service and escalation rules: a user
     * may edit only their own account (an admin may edit anyone), and only an admin may change a user's
     * [role][User.role] or [active][User.active] flag. A non-admin update keeps the target's existing role
     * and active state, so nobody can promote or reactivate themselves.
     *
     * @param user       the user to update
     * @param actingUser the authenticated user attempting the update
     * @return the persisted, updated user
     * @throws ForbiddenException if [actingUser] may neither edit the target nor make the change they sent
     */
    fun update(
        user: User,
        actingUser: User
    ): User

    /**
     * Rotates a member's capability token, invalidating the previously printed QR code and issuing a new
     * capability URL. Admin-only.
     *
     * @param userId     the id of the user whose token to rotate
     * @param actingUser the authenticated user attempting the rotation
     * @return the persisted user with the new capability token
     * @throws NotFoundException if no user exists with [userId]
     * @throws ForbiddenException if [actingUser] is not an admin
     */
    fun rotateCapabilityToken(
        userId: UUID,
        actingUser: User
    ): User
}
