package de.seuhd.campuscoffee.domain.implementation

import de.seuhd.campuscoffee.domain.exceptions.DuplicationException
import de.seuhd.campuscoffee.domain.exceptions.NotFoundException
import de.seuhd.campuscoffee.domain.model.objects.DomainModel
import de.seuhd.campuscoffee.domain.ports.data.CrudDataService
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.doNothing
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Unit tests for the abstract CrudServiceImpl base class, using test-only implementations to test it
 * in isolation.
 */
@ExtendWith(MockitoExtension::class)
class CrudServiceTest {
    /** Test-only domain model. */
    private data class TestDomain(
        override val id: Long?,
        val name: String
    ) : DomainModel<Long>

    /** Test-only concrete implementation of CrudServiceImpl. */
    private class TestCrudServiceImpl(
        private val dataService: CrudDataService<TestDomain, Long>
    ) : CrudServiceImpl<TestDomain, Long>(TestDomain::class.java) {
        override fun dataService(): CrudDataService<TestDomain, Long> = dataService
    }

    @Mock
    private lateinit var dataService: CrudDataService<TestDomain, Long>

    @InjectMocks
    private lateinit var crudService: TestCrudServiceImpl

    @Test
    fun `upsert propagates a DuplicationException from the data service`() {
        val domainObject = TestDomain(null, "duplicate")
        whenever(dataService.upsert(domainObject))
            .thenThrow(DuplicationException(TestDomain::class.java, "name", "duplicate"))

        assertThrows<DuplicationException> { crudService.upsert(domainObject) }
        verify(dataService).upsert(domainObject)
    }

    @Test
    fun `delete delegates to the data service`() {
        doNothing().whenever(dataService).delete(1L)

        crudService.delete(1L)

        verify(dataService).delete(1L)
    }

    @Test
    fun `delete propagates NotFoundException from the data service`() {
        doThrow(NotFoundException(TestDomain::class.java, 2L)).whenever(dataService).delete(2L)

        assertThrows<NotFoundException> { crudService.delete(2L) }
        verify(dataService).delete(2L)
    }
}
