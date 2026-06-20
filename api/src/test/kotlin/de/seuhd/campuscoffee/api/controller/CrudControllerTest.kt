package de.seuhd.campuscoffee.api.controller

import de.seuhd.campuscoffee.api.dtos.Dto
import de.seuhd.campuscoffee.api.mapper.DtoMapper
import de.seuhd.campuscoffee.domain.model.DomainModel
import de.seuhd.campuscoffee.domain.ports.api.CrudService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import java.util.UUID

/**
 * Unit tests for the generic [CrudController] base via a minimal test subclass, covering the create/update
 * id guards and the delegation to the service and mapper that no concrete controller exercises directly.
 */
class CrudControllerTest {
    private data class Thing(
        override val id: UUID?
    ) : DomainModel<UUID>

    private data class ThingDto(
        override val id: UUID?
    ) : Dto<UUID>

    private class ThingController(
        private val svc: CrudService<Thing, UUID>,
        private val map: DtoMapper<Thing, ThingDto>
    ) : CrudController<Thing, ThingDto, UUID>() {
        override fun service(): CrudService<Thing, UUID> = svc

        override fun mapper(): DtoMapper<Thing, ThingDto> = map
    }

    private val service = mock<CrudService<Thing, UUID>>()
    private val mapper = mock<DtoMapper<Thing, ThingDto>>()
    private val controller = ThingController(service, mapper)
    private val id = UUID.randomUUID()

    @BeforeEach
    fun setRequest() {
        val request = MockHttpServletRequest("POST", "/api/things")
        RequestContextHolder.setRequestAttributes(ServletRequestAttributes(request))
    }

    @AfterEach
    fun clearRequest() = RequestContextHolder.resetRequestAttributes()

    @Test
    fun `getAll maps every domain object to its DTO`() {
        whenever(service.getAll()).thenReturn(listOf(Thing(id)))
        whenever(mapper.fromDomain(any())).thenReturn(ThingDto(id))

        assertThat(controller.getAll().body).containsExactly(ThingDto(id))
    }

    @Test
    fun `getById maps the found domain object to its DTO`() {
        whenever(service.getById(id)).thenReturn(Thing(id))
        whenever(mapper.fromDomain(Thing(id))).thenReturn(ThingDto(id))

        assertThat(controller.getById(id).body).isEqualTo(ThingDto(id))
    }

    @Test
    fun `create persists the DTO and returns 201 with a location`() {
        whenever(mapper.toDomain(ThingDto(null))).thenReturn(Thing(null))
        whenever(service.upsert(Thing(null))).thenReturn(Thing(id))
        whenever(mapper.fromDomain(Thing(id))).thenReturn(ThingDto(id))

        val response = controller.create(ThingDto(null))

        assertThat(response.statusCode.value()).isEqualTo(201)
        assertThat(response.headers.location.toString()).endsWith("/$id")
    }

    @Test
    fun `create rejects a DTO that already carries an id`() {
        assertThatThrownBy { controller.create(ThingDto(id)) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `update persists the DTO when the path and body ids match`() {
        whenever(mapper.toDomain(ThingDto(id))).thenReturn(Thing(id))
        whenever(service.upsert(Thing(id))).thenReturn(Thing(id))
        whenever(mapper.fromDomain(Thing(id))).thenReturn(ThingDto(id))

        assertThat(controller.update(id, ThingDto(id)).statusCode.value()).isEqualTo(200)
    }

    @Test
    fun `update rejects mismatched path and body ids`() {
        assertThatThrownBy { controller.update(UUID.randomUUID(), ThingDto(id)) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `delete removes the resource and returns 204`() {
        val response = controller.delete(id)

        assertThat(response.statusCode.value()).isEqualTo(204)
        verify(service).delete(id)
    }
}
