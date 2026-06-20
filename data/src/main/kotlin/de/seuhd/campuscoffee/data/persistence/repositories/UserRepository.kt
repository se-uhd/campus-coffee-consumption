package de.seuhd.campuscoffee.data.persistence.repositories

import de.seuhd.campuscoffee.data.persistence.entities.UserEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

/**
 * Repository for persisting user entities.
 */
interface UserRepository : JpaRepository<UserEntity, UUID> {
    /**
     * Returns the user with the given login name, or null if none matches.
     *
     * @param loginName the login name to look up
     */
    fun findByLoginName(loginName: String): UserEntity?

    /**
     * Returns the user holding the given capability token, or null if none matches (an unknown or rotated
     * token), so the authentication filter can answer 401 without an exception.
     *
     * @param capabilityToken the capability token to look up
     */
    fun findByCapabilityToken(capabilityToken: String): UserEntity?
}
