package de.seuhd.campuscoffee.domain.implementation

import de.seuhd.campuscoffee.domain.exceptions.ConflictException
import de.seuhd.campuscoffee.domain.exceptions.ForbiddenException
import de.seuhd.campuscoffee.domain.exceptions.ValidationException
import de.seuhd.campuscoffee.domain.model.CancellableIncrement
import de.seuhd.campuscoffee.domain.model.CoffeeBean
import de.seuhd.campuscoffee.domain.model.CoffeeRating
import de.seuhd.campuscoffee.domain.model.Role
import de.seuhd.campuscoffee.domain.model.User
import de.seuhd.campuscoffee.domain.ports.api.CoffeeBeanService
import de.seuhd.campuscoffee.domain.ports.data.ActivityDataService
import de.seuhd.campuscoffee.domain.ports.data.BalanceLockService
import de.seuhd.campuscoffee.domain.ports.data.CoffeeRatingDataService
import de.seuhd.campuscoffee.domain.ports.data.UserDataService
import org.assertj.core.api.Assertions.assertThat
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
 * Unit tests for [CoffeeRatingServiceImpl], mocking the ports. The invariants under test: only the active
 * owner rates, the value stays within one to five, a rating needs a cancellable cup within its grace window,
 * a repeat within the window updates the one vote (rather than adding another), a merged bean resolves to its
 * canonical target, and the prompt reflects the current window's vote or the default bean.
 */
class CoffeeRatingServiceTest {
    private val coffeeRatingDataService: CoffeeRatingDataService = mock()
    private val coffeeBeanService: CoffeeBeanService = mock()
    private val activityDataService: ActivityDataService = mock()
    private val userDataService: UserDataService = mock()
    private val balanceLock: BalanceLockService = mock()
    private val gracePeriod: Duration = Duration.ofMinutes(5)
    private val service =
        CoffeeRatingServiceImpl(
            coffeeRatingDataService,
            coffeeBeanService,
            activityDataService,
            userDataService,
            ConsumptionProperties(gracePeriod),
            balanceLock
        )

    private val userId: UUID = UUID(0L, 1L)
    private val beanId: UUID = UUID(0L, 2L)

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
    private val bean = CoffeeBean(id = beanId, name = "Ethiopia")

    private fun now(): LocalDateTime = LocalDateTime.now(ZoneId.of("UTC"))

    /** Stubs a fresh cancellable increment (within grace) for the owner. */
    private fun stubCancellableWindow(createdAt: LocalDateTime = now()) {
        whenever(userDataService.getById(userId)).thenReturn(user)
        whenever(activityDataService.lastCancellableIncrement(userId, user.loginName))
            .thenReturn(CancellableIncrement(createdAt, priceCents = 50))
    }

    @Test
    fun `rateCurrentBean creates a vote when none exists in the window`() {
        stubCancellableWindow()
        whenever(coffeeBeanService.getById(beanId)).thenReturn(bean)
        whenever(coffeeRatingDataService.findCurrentWindowVote(eq(userId), any())).thenReturn(null)
        whenever(coffeeRatingDataService.upsert(any())).thenAnswer { it.arguments[0] as CoffeeRating }

        val rating = service.rateCurrentBean(userId, beanId, 4, user)

        assertThat(rating.value).isEqualTo(4)
        assertThat(rating.bean).isEqualTo(bean)
        assertThat(rating.user).isEqualTo(user)
    }

    @Test
    fun `rateCurrentBean updates the existing vote within the same window`() {
        stubCancellableWindow()
        whenever(coffeeBeanService.getById(beanId)).thenReturn(bean)
        val existing = CoffeeRating(id = UUID(0L, 9L), createdAt = now(), user = user, bean = bean, value = 2)
        whenever(coffeeRatingDataService.findCurrentWindowVote(eq(userId), any())).thenReturn(existing)
        whenever(coffeeRatingDataService.upsert(any())).thenAnswer { it.arguments[0] as CoffeeRating }

        val rating = service.rateCurrentBean(userId, beanId, 5, user)

        assertThat(rating.id).isEqualTo(existing.id)
        assertThat(rating.value).isEqualTo(5)
    }

    @Test
    fun `rateCurrentBean resolves a merged bean to its canonical target`() {
        stubCancellableWindow()
        val canonicalId = UUID(0L, 3L)
        val canonical = CoffeeBean(id = canonicalId, name = "Ethiopia Yirgacheffe")
        whenever(coffeeBeanService.getById(beanId)).thenReturn(bean.copy(active = false, mergedIntoId = canonicalId))
        whenever(coffeeBeanService.getById(canonicalId)).thenReturn(canonical)
        whenever(coffeeRatingDataService.findCurrentWindowVote(eq(userId), any())).thenReturn(null)
        whenever(coffeeRatingDataService.upsert(any())).thenAnswer { it.arguments[0] as CoffeeRating }

        val rating = service.rateCurrentBean(userId, beanId, 3, user)

        assertThat(rating.bean).isEqualTo(canonical)
    }

    @Test
    fun `rateCurrentBean resolves a chained merge to the final canonical bean`() {
        stubCancellableWindow()
        val midId = UUID(0L, 3L)
        val finalId = UUID(0L, 4L)
        val finalBean = CoffeeBean(id = finalId, name = "Ethiopia Sidamo")
        // beanId -> midId (a tombstone) -> finalId (live); a single hop would stop on the mid tombstone
        whenever(coffeeBeanService.getById(beanId)).thenReturn(bean.copy(active = false, mergedIntoId = midId))
        whenever(coffeeBeanService.getById(midId))
            .thenReturn(CoffeeBean(id = midId, name = "Ethiopia Mid", active = false, mergedIntoId = finalId))
        whenever(coffeeBeanService.getById(finalId)).thenReturn(finalBean)
        whenever(coffeeRatingDataService.findCurrentWindowVote(eq(userId), any())).thenReturn(null)
        whenever(coffeeRatingDataService.upsert(any())).thenAnswer { it.arguments[0] as CoffeeRating }

        val rating = service.rateCurrentBean(userId, beanId, 3, user)

        assertThat(rating.bean).isEqualTo(finalBean)
    }

    @Test
    fun `rateCurrentBean by a non-owner non-admin throws ForbiddenException`() {
        val other = user.copy(id = UUID(0L, 7L), loginName = "other")
        assertThrows<ForbiddenException> { service.rateCurrentBean(userId, beanId, 4, other) }
        verify(coffeeRatingDataService, never()).upsert(any())
    }

    @Test
    fun `rateCurrentBean by an admin casts the vote on the owner's behalf`() {
        val admin =
            user.copy(id = UUID(0L, 99L), loginName = "jane", role = Role.ADMIN)
        stubCancellableWindow()
        whenever(coffeeBeanService.getById(beanId)).thenReturn(bean)
        whenever(coffeeRatingDataService.findCurrentWindowVote(eq(userId), any())).thenReturn(null)
        whenever(coffeeRatingDataService.upsert(any())).thenAnswer { it.arguments[0] as CoffeeRating }

        val rating = service.rateCurrentBean(userId, beanId, 5, admin)

        // the vote is attributed to the drinker (the resolved owner), not the acting admin
        assertThat(rating.user).isEqualTo(user)
        assertThat(rating.value).isEqualTo(5)
    }

    @Test
    fun `rateCurrentBean by a deactivated owner throws ForbiddenException`() {
        assertThrows<ForbiddenException> { service.rateCurrentBean(userId, beanId, 4, user.copy(active = false)) }
        verify(coffeeRatingDataService, never()).upsert(any())
    }

    @Test
    fun `rateCurrentBean with a value out of range throws ValidationException`() {
        assertThrows<ValidationException> { service.rateCurrentBean(userId, beanId, 6, user) }
        assertThrows<ValidationException> { service.rateCurrentBean(userId, beanId, 0, user) }
        verify(coffeeRatingDataService, never()).upsert(any())
    }

    @Test
    fun `rateCurrentBean with no cancellable cup throws ConflictException`() {
        whenever(userDataService.getById(userId)).thenReturn(user)
        whenever(activityDataService.lastCancellableIncrement(userId, user.loginName)).thenReturn(null)

        assertThrows<ConflictException> { service.rateCurrentBean(userId, beanId, 4, user) }
        verify(coffeeRatingDataService, never()).upsert(any())
    }

    @Test
    fun `rateCurrentBean past the grace window throws ConflictException`() {
        stubCancellableWindow(createdAt = now().minusMinutes(10))
        whenever(coffeeBeanService.getById(beanId)).thenReturn(bean)

        assertThrows<ConflictException> { service.rateCurrentBean(userId, beanId, 4, user) }
        verify(coffeeRatingDataService, never()).upsert(any())
    }

    @Test
    fun `clearVoteInWindow deletes the window vote when present and is a no-op otherwise`() {
        val vote = CoffeeRating(id = UUID(0L, 9L), createdAt = now(), user = user, bean = bean, value = 3)
        whenever(coffeeRatingDataService.findCurrentWindowVote(eq(userId), any())).thenReturn(vote, null)

        service.clearVoteInWindow(userId, now())
        service.clearVoteInWindow(userId, now())

        verify(coffeeRatingDataService).delete(vote.id!!)
    }

    @Test
    fun `promptFor reports cannot-rate with no cancellable increment`() {
        val prompt = service.promptFor(userId, null)

        assertThat(prompt.canRate).isFalse()
        assertThat(prompt.defaultBeanId).isNull()
        assertThat(prompt.value).isNull()
    }

    @Test
    fun `promptFor prefills the current window vote`() {
        val increment = CancellableIncrement(now(), priceCents = 50)
        val vote = CoffeeRating(id = UUID(0L, 9L), createdAt = now(), user = user, bean = bean, value = 4)
        whenever(coffeeRatingDataService.findCurrentWindowVote(eq(userId), any())).thenReturn(vote)

        val prompt = service.promptFor(userId, increment)

        assertThat(prompt.canRate).isTrue()
        assertThat(prompt.defaultBeanId).isEqualTo(beanId)
        assertThat(prompt.value).isEqualTo(4)
    }

    @Test
    fun `promptFor defaults to the bean most recently rated by anyone when there is no vote yet`() {
        val increment = CancellableIncrement(now(), priceCents = 50)
        whenever(coffeeRatingDataService.findCurrentWindowVote(eq(userId), any())).thenReturn(null)
        // the most recently rated bean is chosen ahead of the most recently purchased one, which the elvis
        // chain never consults once a rating exists, so stubbing it would be dead
        whenever(coffeeBeanService.mostRecentlyRated()).thenReturn(bean)

        val prompt = service.promptFor(userId, increment)

        assertThat(prompt.canRate).isTrue()
        assertThat(prompt.defaultBeanId).isEqualTo(beanId)
        assertThat(prompt.value).isNull()
    }

    @Test
    fun `promptFor falls back to the most recently purchased bean when nothing has been rated yet`() {
        val increment = CancellableIncrement(now(), priceCents = 50)
        whenever(coffeeRatingDataService.findCurrentWindowVote(eq(userId), any())).thenReturn(null)
        whenever(coffeeBeanService.mostRecentlyRated()).thenReturn(null)
        whenever(coffeeBeanService.mostRecentlyPurchased()).thenReturn(bean)

        val prompt = service.promptFor(userId, increment)

        assertThat(prompt.canRate).isTrue()
        assertThat(prompt.defaultBeanId).isEqualTo(beanId)
        assertThat(prompt.value).isNull()
    }
}
