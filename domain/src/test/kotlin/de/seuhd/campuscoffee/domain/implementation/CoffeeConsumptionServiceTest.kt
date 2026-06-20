package de.seuhd.campuscoffee.domain.implementation

import de.seuhd.campuscoffee.domain.exceptions.ConflictException
import de.seuhd.campuscoffee.domain.exceptions.ForbiddenException
import de.seuhd.campuscoffee.domain.exceptions.ValidationException
import de.seuhd.campuscoffee.domain.model.objects.CoffeeConsumption
import de.seuhd.campuscoffee.domain.model.objects.ConsumptionChange
import de.seuhd.campuscoffee.domain.model.objects.Role
import de.seuhd.campuscoffee.domain.model.objects.User
import de.seuhd.campuscoffee.domain.ports.ChangeNoteContext
import de.seuhd.campuscoffee.domain.ports.data.CoffeeConsumptionDataService
import de.seuhd.campuscoffee.domain.ports.data.ConsumptionHistoryDataService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.LocalDateTime
import java.util.UUID

/**
 * Unit tests for CoffeeConsumptionServiceImpl, mocking the data ports. A real ChangeNoteContext is used
 * (it is a dependency-free thread-local holder) so the note-recording path runs unchanged.
 */
class CoffeeConsumptionServiceTest {
    private val dataService: CoffeeConsumptionDataService = mock()
    private val historyService: ConsumptionHistoryDataService = mock()
    private val changeNoteContext = ChangeNoteContext()

    private lateinit var service: CoffeeConsumptionServiceImpl

    private val memberId: UUID = UUID(0L, 1L)
    private val adminId: UUID = UUID(0L, 99L)

    private val member =
        User(
            id = memberId,
            loginName = "max",
            emailAddress = "max@se.de",
            firstName = "Max",
            lastName = "M",
            role = Role.USER,
            active = true
        )
    private val admin =
        User(
            id = adminId,
            loginName = "jane",
            emailAddress = "jane@se.de",
            firstName = "Jane",
            lastName = "D",
            role = Role.ADMIN,
            active = true
        )

    private fun consumption(count: Int) =
        CoffeeConsumption(
            id = UUID(0L, 500L),
            createdAt = LocalDateTime.now(),
            updatedAt = LocalDateTime.now(),
            user = member,
            count = count
        )

    @BeforeEach
    fun setUp() {
        service = CoffeeConsumptionServiceImpl(dataService, historyService, changeNoteContext)
    }

    @Test
    fun `applyDelta of +1 increments the owner's count by one`() {
        whenever(dataService.getByUserId(memberId)).thenReturn(consumption(2))
        whenever(dataService.upsert(any())).thenAnswer { it.arguments[0] as CoffeeConsumption }

        val result = service.applyDelta(memberId, 1, member)

        assertThat(result.count).isEqualTo(3)
    }

    @Test
    fun `applyDelta of -1 decrements the owner's count by one`() {
        whenever(dataService.getByUserId(memberId)).thenReturn(consumption(2))
        whenever(dataService.upsert(any())).thenAnswer { it.arguments[0] as CoffeeConsumption }

        val result = service.applyDelta(memberId, -1, member)

        assertThat(result.count).isEqualTo(1)
    }

    @Test
    fun `applyDelta with a delta other than plus or minus one throws ValidationException`() {
        assertThrows<ValidationException> { service.applyDelta(memberId, 2, member) }
        verify(dataService, never()).upsert(any())
    }

    @Test
    fun `applyDelta of -1 at zero throws ConflictException for a negative count`() {
        whenever(dataService.getByUserId(memberId)).thenReturn(consumption(0))

        assertThrows<ConflictException> { service.applyDelta(memberId, -1, member) }
        verify(dataService, never()).upsert(any())
    }

    @Test
    fun `applyDelta by a non-owner non-admin throws ForbiddenException`() {
        val other = member.copy(id = UUID(0L, 7L), loginName = "other")

        assertThrows<ForbiddenException> { service.applyDelta(memberId, 1, other) }
    }

    @Test
    fun `applyDelta by a deactivated owner throws ForbiddenException`() {
        val deactivated = member.copy(active = false)

        assertThrows<ForbiddenException> { service.applyDelta(memberId, 1, deactivated) }
        verify(dataService, never()).upsert(any())
    }

    @Test
    fun `applyDelta by an admin on another member's count is allowed`() {
        whenever(dataService.getByUserId(memberId)).thenReturn(consumption(2))
        whenever(dataService.upsert(any())).thenAnswer { it.arguments[0] as CoffeeConsumption }

        val result = service.applyDelta(memberId, 1, admin)

        assertThat(result.count).isEqualTo(3)
    }

    @Test
    fun `setTotal by an admin overrides the count to the given value`() {
        whenever(dataService.getByUserId(memberId)).thenReturn(consumption(8))
        whenever(dataService.upsert(any())).thenAnswer { it.arguments[0] as CoffeeConsumption }

        val result = service.setTotal(memberId, 0, "paid in cash", admin)

        assertThat(result.count).isEqualTo(0)
    }

    @Test
    fun `setTotal by a non-admin throws ForbiddenException`() {
        assertThrows<ForbiddenException> { service.setTotal(memberId, 0, null, member) }
        verify(dataService, never()).upsert(any())
    }

    @Test
    fun `setTotal with a negative total throws ValidationException`() {
        assertThrows<ValidationException> { service.setTotal(memberId, -1, null, admin) }
        verify(dataService, never()).upsert(any())
    }

    @Test
    fun `getByUserId by a non-owner non-admin throws ForbiddenException`() {
        val other = member.copy(id = UUID(0L, 7L), loginName = "other")

        assertThrows<ForbiddenException> { service.getByUserId(memberId, other) }
        verify(dataService, never()).getByUserId(any())
    }

    @Test
    fun `recentChanges returns the changes from the history service for the owner`() {
        val change =
            ConsumptionChange(count = 1, delta = 1, createdAt = LocalDateTime.now(), createdBy = "max", note = null)
        whenever(dataService.getByUserId(memberId)).thenReturn(consumption(1))
        whenever(historyService.changes(eq(UUID(0L, 500L)), eq(5), eq(0))).thenReturn(listOf(change))

        val result = service.recentChanges(memberId, 5, 0, member)

        assertThat(result).containsExactly(change)
    }

    @Test
    fun `createForUser creates a consumption at count zero`() {
        whenever(dataService.upsert(any())).thenAnswer { it.arguments[0] as CoffeeConsumption }

        val result = service.createForUser(member)

        assertThat(result.count).isEqualTo(0)
        assertThat(result.user).isEqualTo(member)
    }
}
