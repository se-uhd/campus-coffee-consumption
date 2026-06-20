package de.seuhd.campuscoffee.data.implementations

import de.seuhd.campuscoffee.data.mapper.UserEntityMapper
import de.seuhd.campuscoffee.data.persistence.entities.UserEntity
import de.seuhd.campuscoffee.data.persistence.repositories.UserRepository
import de.seuhd.campuscoffee.domain.exceptions.ConcurrentUpdateException
import de.seuhd.campuscoffee.domain.model.Role
import de.seuhd.campuscoffee.domain.model.User
import de.seuhd.campuscoffee.domain.tests.TestFixtures
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.hibernate.exception.ConstraintViolationException
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.orm.ObjectOptimisticLockingFailureException
import java.util.Optional
import java.util.UUID

/**
 * The repository is mocked to force the relational adapter's failure paths: a JPA optimistic-locking
 * failure maps to a [ConcurrentUpdateException], and a constraint violation that matches no declared
 * unique constraint propagates unchanged.
 */
class CrudDataServiceOptimisticLockTest {
    private val repository = mock<UserRepository>()
    private val mapper = mock<UserEntityMapper>()

    // the constructor requires a generator even on the update path, which does not assign a new id
    private val service = UserDataServiceImpl(repository, mapper) { UUID.randomUUID() }

    @Test
    fun `upsert maps a JPA optimistic lock failure to ConcurrentUpdateException`() {
        val existing = TestFixtures.admin() // has a non-null id, so upsert takes the update path
        val id = existing.id!!
        whenever(repository.findById(id)).thenReturn(Optional.of(UserEntity()))
        whenever(repository.saveAndFlush(any<UserEntity>()))
            .thenThrow(ObjectOptimisticLockingFailureException(UserEntity::class.java, id))

        assertThatThrownBy { service.upsert(existing) }
            .isInstanceOf(ConcurrentUpdateException::class.java)
    }

    @Test
    fun `upsert rethrows a constraint violation that matches no declared unique constraint`() {
        val hibernate = mock<ConstraintViolationException>()
        whenever(hibernate.constraintName).thenReturn("some_unknown_constraint")
        whenever(mapper.toEntity(any())).thenReturn(UserEntity())
        whenever(repository.saveAndFlush(any<UserEntity>()))
            .thenThrow(DataIntegrityViolationException("could not execute statement", hibernate))

        // a create (no id) so the insert path runs and the violation is not one of the declared constraints
        val newUser =
            User(
                loginName = "x",
                emailAddress = "x@se.de",
                firstName = "X",
                lastName = "Y",
                role = Role.USER,
                active = true,
                capabilityToken = "tok",
                passwordHash = "{noop}h"
            )
        assertThatThrownBy { service.upsert(newUser) }
            .isInstanceOf(DataIntegrityViolationException::class.java)
    }
}
