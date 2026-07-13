package de.seuhd.campuscoffee.domain.implementation

import de.seuhd.campuscoffee.domain.exceptions.ForbiddenException
import de.seuhd.campuscoffee.domain.exceptions.ValidationException
import de.seuhd.campuscoffee.domain.model.CoffeeBean
import de.seuhd.campuscoffee.domain.model.CoffeeRating
import de.seuhd.campuscoffee.domain.model.Expense
import de.seuhd.campuscoffee.domain.model.Role
import de.seuhd.campuscoffee.domain.model.User
import de.seuhd.campuscoffee.domain.ports.data.CoffeeBeanDataService
import de.seuhd.campuscoffee.domain.ports.data.CoffeeRatingDataService
import de.seuhd.campuscoffee.domain.ports.data.ExpenseDataService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.LocalDateTime
import java.util.UUID

/**
 * Unit tests for [CoffeeBeanServiceImpl], mocking the data ports. Covers the selectable filter, resolve-or-
 * create by normalized name, the admin-only rename and merge (with the merge tombstone), and the ratings
 * aggregation, including counting a merged bean's votes and purchases under its canonical target.
 */
class CoffeeBeanServiceTest {
    private val coffeeBeanDataService: CoffeeBeanDataService = mock()
    private val coffeeRatingDataService: CoffeeRatingDataService = mock()
    private val expenseDataService: ExpenseDataService = mock()
    private val service = CoffeeBeanServiceImpl(coffeeBeanDataService, coffeeRatingDataService, expenseDataService)

    private val admin =
        User(
            id = UUID(0L, 99L),
            loginName = "jane",
            emailAddress = "jane@se.de",
            firstName = "Jane",
            lastName = "D",
            role = Role.ADMIN,
            active = true
        )
    private val user = admin.copy(id = UUID(0L, 1L), loginName = "max", role = Role.USER)

    private val idA: UUID = UUID(0L, 10L)
    private val idB: UUID = UUID(0L, 11L)
    private val beanA = CoffeeBean(id = idA, name = "Ethiopia")
    private val beanB = CoffeeBean(id = idB, name = "Colombia")

    @Test
    fun `listSelectable returns only live canonical beans, sorted by name`() {
        val merged = CoffeeBean(id = UUID(0L, 12L), name = "Aardvark", active = false, mergedIntoId = idA)
        val inactive = CoffeeBean(id = UUID(0L, 13L), name = "Brazil", active = false)
        whenever(coffeeBeanDataService.getAll()).thenReturn(listOf(beanA, beanB, merged, inactive))

        val selectable = service.listSelectable()

        assertThat(selectable.map { it.name }).containsExactly("Colombia", "Ethiopia")
    }

    @Test
    fun `resolveOrCreate returns an existing bean without creating one`() {
        whenever(coffeeBeanDataService.findActiveByName("Ethiopia")).thenReturn(beanA)

        val bean = service.resolveOrCreate("  Ethiopia  ")

        assertThat(bean).isEqualTo(beanA)
        verify(coffeeBeanDataService, never()).upsert(any())
    }

    @Test
    fun `resolveOrCreate creates a normalized new bean when none matches`() {
        whenever(coffeeBeanDataService.findActiveByName("House Blend")).thenReturn(null)
        whenever(coffeeBeanDataService.upsert(any())).thenAnswer { it.arguments[0] as CoffeeBean }

        val bean = service.resolveOrCreate("House   Blend")

        assertThat(bean.name).isEqualTo("House Blend")
    }

    @Test
    fun `resolveOrCreate with a blank name throws ValidationException`() {
        assertThrows<ValidationException> { service.resolveOrCreate("   ") }
        verify(coffeeBeanDataService, never()).upsert(any())
    }

    @Test
    fun `rename by a non-admin throws ForbiddenException`() {
        assertThrows<ForbiddenException> { service.rename(idA, "New", user) }
        verify(coffeeBeanDataService, never()).upsert(any())
    }

    @Test
    fun `rename by an admin stores the normalized new name`() {
        whenever(coffeeBeanDataService.getById(idA)).thenReturn(beanA)
        whenever(coffeeBeanDataService.upsert(any())).thenAnswer { it.arguments[0] as CoffeeBean }

        val renamed = service.rename(idA, "  Ethiopia  Sidamo ", admin)

        assertThat(renamed.name).isEqualTo("Ethiopia Sidamo")
    }

    @Test
    fun `merge into itself throws ValidationException`() {
        assertThrows<ValidationException> { service.merge(idA, idA, admin) }
    }

    @Test
    fun `merge into a non-canonical target throws ValidationException`() {
        val mergedTarget = beanB.copy(active = false, mergedIntoId = idA)
        whenever(coffeeBeanDataService.getById(idB)).thenReturn(mergedTarget)

        assertThrows<ValidationException> { service.merge(idA, idB, admin) }
        verify(coffeeBeanDataService, never()).upsert(any())
    }

    @Test
    fun `merge tombstones the source and points it at the canonical target`() {
        whenever(coffeeBeanDataService.getById(idB)).thenReturn(beanB)
        whenever(coffeeBeanDataService.getById(idA)).thenReturn(beanA)
        whenever(coffeeBeanDataService.upsert(any())).thenAnswer { it.arguments[0] as CoffeeBean }

        val merged = service.merge(idA, idB, admin)

        assertThat(merged.active).isFalse()
        assertThat(merged.mergedIntoId).isEqualTo(beanB.id)
    }

    @Test
    fun `mostRecentlyRated returns the bean of the most recent rating when it is live`() {
        whenever(coffeeBeanDataService.findMostRecentlyRated()).thenReturn(beanA)

        assertThat(service.mostRecentlyRated()).isEqualTo(beanA)
    }

    @Test
    fun `mostRecentlyRated resolves a since-merged bean to its canonical target`() {
        val tombstone = beanA.copy(active = false, mergedIntoId = idB)
        whenever(coffeeBeanDataService.findMostRecentlyRated()).thenReturn(tombstone)
        whenever(coffeeBeanDataService.getById(idB)).thenReturn(beanB)

        assertThat(service.mostRecentlyRated()).isEqualTo(beanB)
    }

    @Test
    fun `mostRecentlyRated follows a chained merge to the final canonical bean`() {
        val idC = UUID(0L, 15L)
        val beanC = CoffeeBean(id = idC, name = "Kenya")
        val tombstoneA = beanA.copy(active = false, mergedIntoId = idB)
        val tombstoneB = beanB.copy(active = false, mergedIntoId = idC)
        whenever(coffeeBeanDataService.findMostRecentlyRated()).thenReturn(tombstoneA)
        whenever(coffeeBeanDataService.getById(idB)).thenReturn(tombstoneB)
        whenever(coffeeBeanDataService.getById(idC)).thenReturn(beanC)

        assertThat(service.mostRecentlyRated()).isEqualTo(beanC)
    }

    @Test
    fun `mostRecentlyRated returns null when nothing has been rated`() {
        whenever(coffeeBeanDataService.findMostRecentlyRated()).thenReturn(null)

        assertThat(service.mostRecentlyRated()).isNull()
    }

    @Test
    fun `ratings count a merged bean's votes and purchases under its canonical target`() {
        val mergedIntoA = CoffeeBean(id = UUID(0L, 14L), name = "Ethiopia Old", active = false, mergedIntoId = idA)
        whenever(coffeeBeanDataService.getAll()).thenReturn(listOf(beanA, beanB, mergedIntoA))
        val t0 = LocalDateTime.parse("2026-01-01T10:00:00")
        whenever(coffeeRatingDataService.getAll()).thenReturn(
            listOf(
                rating(beanA, 4, t0),
                rating(beanA, 2, t0.plusHours(1)),
                // a vote on the merged bean counts under canonical A
                rating(mergedIntoA, 3, t0.plusHours(2))
            )
        )
        whenever(expenseDataService.getAll()).thenReturn(listOf(purchase(beanA, t0.plusDays(1))))

        val ratings = service.ratings()

        // A and B are the canonical beans; the merged one is not its own row
        assertThat(ratings.map { it.bean.id }).containsExactly(idA, idB)
        val a = ratings.first { it.bean.id == idA }
        assertThat(a.voteCount).isEqualTo(3)
        assertThat(a.averageValue).isEqualTo(3.0)
        assertThat(a.latestRatingAt).isEqualTo(t0.plusHours(2))
        assertThat(a.latestPurchaseAt).isEqualTo(t0.plusDays(1))
        // B has no votes: it sorts after A and reports a null average
        val b = ratings.first { it.bean.id == idB }
        assertThat(b.voteCount).isEqualTo(0)
        assertThat(b.averageValue).isNull()
    }

    private fun rating(
        bean: CoffeeBean,
        value: Int,
        createdAt: LocalDateTime
    ) = CoffeeRating(id = UUID.randomUUID(), createdAt = createdAt, user = user, bean = bean, value = value)

    private fun purchase(
        bean: CoffeeBean,
        createdAt: LocalDateTime
    ) = Expense(
        id = UUID.randomUUID(),
        createdAt = createdAt,
        buyer = user,
        bean = bean,
        weightGrams = 1000,
        amountCents = 900,
        privateAmountCents = 900,
        kittyAmountCents = 0
    )
}
