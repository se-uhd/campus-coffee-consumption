package de.seuhd.campuscoffee.domain.implementation

import de.seuhd.campuscoffee.domain.exceptions.ConflictException
import de.seuhd.campuscoffee.domain.exceptions.ForbiddenException
import de.seuhd.campuscoffee.domain.exceptions.ValidationException
import de.seuhd.campuscoffee.domain.model.CancellableIncrement
import de.seuhd.campuscoffee.domain.model.ChangeNoteContext
import de.seuhd.campuscoffee.domain.model.CoffeeConsumption
import de.seuhd.campuscoffee.domain.model.ConsumptionChange
import de.seuhd.campuscoffee.domain.model.Role
import de.seuhd.campuscoffee.domain.model.User
import de.seuhd.campuscoffee.domain.ports.data.ActivityDataService
import de.seuhd.campuscoffee.domain.ports.data.CoffeeConsumptionDataService
import de.seuhd.campuscoffee.domain.ports.data.ConsumptionHistoryDataService
import de.seuhd.campuscoffee.domain.ports.data.UserDataService
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
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

/**
 * Unit tests for CoffeeConsumptionServiceImpl, mocking the data ports. A real ChangeNoteContext is used
 * (it is a dependency-free thread-local holder) so the note-recording path runs unchanged. A five-minute
 * grace period is configured so a freshly recorded increment is within it.
 */
class CoffeeConsumptionServiceTest {
    private val dataService: CoffeeConsumptionDataService = mock()
    private val historyService: ConsumptionHistoryDataService = mock()
    private val activityDataService: ActivityDataService = mock()
    private val userDataService: UserDataService = mock()
    private val changeNoteContext = ChangeNoteContext()
    private val gracePeriod: Duration = Duration.ofMinutes(5)

    private lateinit var service: CoffeeConsumptionServiceImpl

    private val userId: UUID = UUID(0L, 1L)
    private val adminId: UUID = UUID(0L, 99L)

    private val user =
        User(
            id = userId,
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
            user = user,
            count = count
        )

    private fun now(): LocalDateTime = LocalDateTime.now(ZoneId.of("UTC"))

    @BeforeEach
    fun setUp() {
        service =
            CoffeeConsumptionServiceImpl(
                dataService,
                historyService,
                activityDataService,
                userDataService,
                changeNoteContext,
                ConsumptionProperties(gracePeriod)
            )
    }

    @Test
    fun `applyDelta of +1 increments the owner's count by one`() {
        whenever(dataService.getByUserId(userId)).thenReturn(consumption(2))
        whenever(dataService.upsert(any())).thenAnswer { it.arguments[0] as CoffeeConsumption }

        val result = service.applyDelta(userId, 1, user)

        assertThat(result.count).isEqualTo(3)
    }

    @Test
    fun `applyDelta of -1 decrements the owner's count by one`() {
        whenever(dataService.getByUserId(userId)).thenReturn(consumption(2))
        whenever(dataService.upsert(any())).thenAnswer { it.arguments[0] as CoffeeConsumption }

        val result = service.applyDelta(userId, -1, user)

        assertThat(result.count).isEqualTo(1)
    }

    @Test
    fun `applyDelta with a delta other than plus or minus one throws ValidationException`() {
        assertThrows<ValidationException> { service.applyDelta(userId, 2, user) }
        verify(dataService, never()).upsert(any())
    }

    @Test
    fun `applyDelta of -1 at zero throws ConflictException for a negative count`() {
        whenever(dataService.getByUserId(userId)).thenReturn(consumption(0))

        assertThrows<ConflictException> { service.applyDelta(userId, -1, user) }
        verify(dataService, never()).upsert(any())
    }

    @Test
    fun `applyDelta by a non-owner non-admin throws ForbiddenException`() {
        val other = user.copy(id = UUID(0L, 7L), loginName = "other")

        assertThrows<ForbiddenException> { service.applyDelta(userId, 1, other) }
    }

    @Test
    fun `applyDelta by a deactivated owner throws ForbiddenException`() {
        val deactivated = user.copy(active = false)

        assertThrows<ForbiddenException> { service.applyDelta(userId, 1, deactivated) }
        verify(dataService, never()).upsert(any())
    }

    @Test
    fun `applyDelta by an admin on another user's count is allowed`() {
        whenever(dataService.getByUserId(userId)).thenReturn(consumption(2))
        whenever(dataService.upsert(any())).thenAnswer { it.arguments[0] as CoffeeConsumption }

        val result = service.applyDelta(userId, 1, admin)

        assertThat(result.count).isEqualTo(3)
    }

    @Test
    fun `setTotal by an admin overrides the count to the given value`() {
        whenever(dataService.getByUserId(userId)).thenReturn(consumption(8))
        whenever(dataService.upsert(any())).thenAnswer { it.arguments[0] as CoffeeConsumption }

        val result = service.setTotal(userId, 0, "paid in cash", admin)

        assertThat(result.count).isEqualTo(0)
    }

    @Test
    fun `setTotal by a non-admin throws ForbiddenException`() {
        assertThrows<ForbiddenException> { service.setTotal(userId, 0, null, user) }
        verify(dataService, never()).upsert(any())
    }

    @Test
    fun `setTotal with a negative total throws ValidationException`() {
        assertThrows<ValidationException> { service.setTotal(userId, -1, null, admin) }
        verify(dataService, never()).upsert(any())
    }

    @Test
    fun `getByUserId by a non-owner non-admin throws ForbiddenException`() {
        val other = user.copy(id = UUID(0L, 7L), loginName = "other")

        assertThrows<ForbiddenException> { service.getByUserId(userId, other) }
        verify(dataService, never()).getByUserId(any())
    }

    @Test
    fun `recentChanges returns the changes from the history service for the owner`() {
        val change =
            ConsumptionChange(count = 1, delta = 1, createdAt = LocalDateTime.now(), createdBy = "max", note = null)
        whenever(dataService.getByUserId(userId)).thenReturn(consumption(1))
        whenever(historyService.changes(eq(UUID(0L, 500L)), eq(5), eq(0))).thenReturn(listOf(change))

        val result = service.recentChanges(userId, 5, 0, user)

        assertThat(result).containsExactly(change)
    }

    @Test
    fun `createForUser creates a consumption at count zero`() {
        whenever(dataService.upsert(any())).thenAnswer { it.arguments[0] as CoffeeConsumption }

        val result = service.createForUser(user)

        assertThat(result.count).isEqualTo(0)
        assertThat(result.user).isEqualTo(user)
    }

    @Test
    fun `cancel by the owner within the grace period decrements the count by one`() {
        whenever(activityDataService.lastCancellableIncrement(userId, "max"))
            .thenReturn(CancellableIncrement(createdAt = now(), priceCents = 50))
        whenever(dataService.getByUserId(userId)).thenReturn(consumption(3))
        whenever(dataService.upsert(any())).thenAnswer { it.arguments[0] as CoffeeConsumption }

        val result = service.cancel(userId, user)

        assertThat(result.count).isEqualTo(2)
    }

    @Test
    fun `cancel by an admin throws ForbiddenException`() {
        assertThrows<ForbiddenException> { service.cancel(userId, admin) }
        verify(dataService, never()).upsert(any())
    }

    @Test
    fun `cancel by a deactivated owner throws ForbiddenException`() {
        val deactivated = user.copy(active = false)

        assertThrows<ForbiddenException> { service.cancel(userId, deactivated) }
        verify(dataService, never()).upsert(any())
    }

    @Test
    fun `cancel with no recent coffee to undo throws ConflictException`() {
        whenever(activityDataService.lastCancellableIncrement(userId, "max")).thenReturn(null)

        assertThrows<ConflictException> { service.cancel(userId, user) }
        verify(dataService, never()).upsert(any())
    }

    @Test
    fun `cancel of a coffee whose grace period has passed throws ConflictException`() {
        whenever(activityDataService.lastCancellableIncrement(userId, "max"))
            .thenReturn(CancellableIncrement(createdAt = now().minusHours(1), priceCents = 50))

        assertThrows<ConflictException> { service.cancel(userId, user) }
        verify(dataService, never()).upsert(any())
    }

    @Test
    fun `cancellableIncrement returns the candidate within the grace period`() {
        val candidate = CancellableIncrement(createdAt = now(), priceCents = 50)
        whenever(userDataService.getById(userId)).thenReturn(user)
        whenever(activityDataService.lastCancellableIncrement(userId, "max")).thenReturn(candidate)

        assertThat(service.cancellableIncrement(userId, user)).isEqualTo(candidate)
    }

    @Test
    fun `cancellableIncrement returns null once the grace period has passed`() {
        whenever(userDataService.getById(userId)).thenReturn(user)
        whenever(activityDataService.lastCancellableIncrement(userId, "max"))
            .thenReturn(CancellableIncrement(createdAt = now().minusHours(1), priceCents = 50))

        assertThat(service.cancellableIncrement(userId, user)).isNull()
    }

    @Test
    fun `cancellableIncrement by a non-owner non-admin throws ForbiddenException`() {
        val other = user.copy(id = UUID(0L, 7L), loginName = "other")

        assertThrows<ForbiddenException> { service.cancellableIncrement(userId, other) }
    }
}
