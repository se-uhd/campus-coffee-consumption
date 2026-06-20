package de.seuhd.campuscoffee.domain.ports.data

import de.seuhd.campuscoffee.domain.exceptions.NotFoundException
import de.seuhd.campuscoffee.domain.model.objects.User
import java.util.UUID

/**
 * Port interface for user data operations.
 *
 * This port is implemented by the data layer (adapter) and defines the contract for persistence
 * operations on user entities. Extends the generic [CrudDataService] to inherit common CRUD operations.
 */
interface UserDataService : CrudDataService<User, UUID> {
    /**
     * Retrieves a single user entity by its unique login name and returns it as a domain object.
     *
     * @param loginName the login name of the user to retrieve
     * @return the user with the specified login name
     * @throws NotFoundException if no user exists with the given login name
     */
    fun getByLoginName(loginName: String): User

    /**
     * Looks up a user by their unique capability token, returning null if none matches (an unknown or
     * rotated token), so the authentication filter can answer 401 without an exception.
     *
     * @param capabilityToken the capability token to look up
     * @return the owning user, or null if no user holds that token
     */
    fun findByCapabilityToken(capabilityToken: String): User?
}
