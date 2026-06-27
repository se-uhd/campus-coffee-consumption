package de.seuhd.campuscoffee.data.implementations

import de.seuhd.campuscoffee.data.mapper.UserEntityMapper
import de.seuhd.campuscoffee.data.persistence.repositories.UserRepository
import de.seuhd.campuscoffee.domain.exceptions.DeletionConflictException
import de.seuhd.campuscoffee.domain.ports.system.IdGeneratorService
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.dao.DataIntegrityViolationException
import java.util.UUID

/**
 * Unit tests for [CrudDataServiceImpl]: the delete path's translation of a foreign key violation into a
 * [DeletionConflictException].
 */
class CrudDataServiceImplTest {
    @Test
    fun `delete maps a data integrity violation to DeletionConflictException`() {
        val repository = mock<UserRepository>()
        val id = UUID.randomUUID()
        whenever(repository.existsById(id)).thenReturn(true)
        doThrow(DataIntegrityViolationException("foreign key violation")).whenever(repository).flush()
        val service = UserDataServiceImpl(repository, mock<UserEntityMapper>(), mock<IdGeneratorService>())

        assertThatThrownBy { service.delete(id) }.isInstanceOf(DeletionConflictException::class.java)
    }
}
