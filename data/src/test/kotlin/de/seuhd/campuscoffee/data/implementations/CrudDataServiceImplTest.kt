package de.seuhd.campuscoffee.data.implementations

import de.seuhd.campuscoffee.data.mapper.UserEntityMapper
import de.seuhd.campuscoffee.data.persistence.repositories.UserRepository
import de.seuhd.campuscoffee.domain.exceptions.DeletionConflictException
import de.seuhd.campuscoffee.domain.ports.IdGenerator
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.hibernate.exception.ConstraintViolationException
import org.junit.jupiter.api.Test
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.dao.DataIntegrityViolationException
import java.util.UUID

/**
 * Unit tests for [CrudDataServiceImpl]: the static [CrudDataServiceImpl.constraintNameOf] (which reads the
 * violated constraint name from the data integrity violation's Hibernate cause rather than matching on
 * database-specific message text), and the delete path's translation of a foreign key violation.
 */
class CrudDataServiceImplTest {
    @Test
    fun `delete maps a data integrity violation to DeletionConflictException`() {
        val repository = mock<UserRepository>()
        val id = UUID.randomUUID()
        whenever(repository.existsById(id)).thenReturn(true)
        doThrow(DataIntegrityViolationException("foreign key violation")).whenever(repository).flush()
        val service = UserDataServiceImpl(repository, mock<UserEntityMapper>(), mock<IdGenerator>())

        assertThatThrownBy { service.delete(id) }.isInstanceOf(DeletionConflictException::class.java)
    }

    @Test
    fun `constraintNameOf reads the constraint name from the Hibernate cause`() {
        val reportedName = "some_unique_constraint"
        val hibernateViolation = mock<ConstraintViolationException>()
        whenever(hibernateViolation.constraintName).thenReturn(reportedName)
        val exception = DataIntegrityViolationException("could not execute statement", hibernateViolation)

        assertThat(CrudDataServiceImpl.constraintNameOf(exception)).isEqualTo(reportedName)
    }

    @Test
    fun `constraintNameOf returns null when the cause chain has no Hibernate violation`() {
        val exception =
            DataIntegrityViolationException(
                "could not execute statement",
                RuntimeException("some unrelated database error")
            )

        assertThat(CrudDataServiceImpl.constraintNameOf(exception)).isNull()
    }
}
