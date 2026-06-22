package de.seuhd.campuscoffee.data.implementations

import de.seuhd.campuscoffee.data.constraints.ConstraintMapping
import de.seuhd.campuscoffee.data.mapper.UserEntityMapper
import de.seuhd.campuscoffee.data.persistence.entities.UserEntity
import de.seuhd.campuscoffee.data.persistence.repositories.UserRepository
import de.seuhd.campuscoffee.domain.exceptions.NotFoundException
import de.seuhd.campuscoffee.domain.model.User
import de.seuhd.campuscoffee.domain.ports.IdGenerator
import de.seuhd.campuscoffee.domain.ports.data.UserDataService
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Data-layer adapter implementing the user data service port. Responsible for persistence;
 * business logic lives in the domain service layer.
 */
@Service
class UserDataServiceImpl(
    repository: UserRepository,
    entityMapper: UserEntityMapper,
    idGenerator: IdGenerator
) : CrudDataServiceImpl<User, UserEntity, UserRepository, UUID>(
        repository,
        entityMapper,
        User::class.java,
        // unique constraints on login name, email, and capability token, each reported as a
        // DuplicationException on the offending field
        setOf(
            ConstraintMapping({ it.loginName }, UserEntity.LOGIN_NAME_COLUMN, UserEntity.LOGIN_NAME_UNIQUE_CONSTRAINT),
            ConstraintMapping(
                { it.emailAddress },
                UserEntity.EMAIL_ADDRESS_COLUMN,
                UserEntity.EMAIL_ADDRESS_UNIQUE_CONSTRAINT
            ),
            ConstraintMapping(
                { it.capabilityToken },
                UserEntity.CAPABILITY_TOKEN_COLUMN,
                UserEntity.CAPABILITY_TOKEN_UNIQUE_CONSTRAINT
            )
        ),
        idGenerator
    ),
    UserDataService {
    /**
     * Returns all users in a stable order (by login name ascending). Overrides the base, which uses the
     * repository's default (physically ordered) `findAll`, so that a mutation such as a deactivation or a
     * capability-token rotation never reshuffles the admin user list.
     */
    override fun getAll(): List<User> = repository.findAllByOrderByLoginNameAsc().map { mapper.fromEntity(it) }

    /**
     * Retrieves a user by their unique login name.
     *
     * @throws NotFoundException if no user exists with the given login name
     */
    override fun getByLoginName(loginName: String): User =
        findByFieldOrThrow({ repository.findByLoginName(loginName) }, UserEntity.LOGIN_NAME_COLUMN, loginName)

    override fun findByCapabilityToken(capabilityToken: String): User? =
        repository.findByCapabilityToken(capabilityToken)?.let { mapper.fromEntity(it) }
}
