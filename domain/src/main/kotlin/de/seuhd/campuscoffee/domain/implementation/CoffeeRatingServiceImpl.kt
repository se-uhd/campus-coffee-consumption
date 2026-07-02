package de.seuhd.campuscoffee.domain.implementation

import de.seuhd.campuscoffee.domain.exceptions.ConflictException
import de.seuhd.campuscoffee.domain.exceptions.ForbiddenException
import de.seuhd.campuscoffee.domain.exceptions.ValidationException
import de.seuhd.campuscoffee.domain.model.CancellableIncrement
import de.seuhd.campuscoffee.domain.model.CoffeeBean
import de.seuhd.campuscoffee.domain.model.CoffeeRating
import de.seuhd.campuscoffee.domain.model.CoffeeRatingPrompt
import de.seuhd.campuscoffee.domain.model.Role
import de.seuhd.campuscoffee.domain.model.User
import de.seuhd.campuscoffee.domain.model.persistedId
import de.seuhd.campuscoffee.domain.ports.api.CoffeeBeanService
import de.seuhd.campuscoffee.domain.ports.api.CoffeeRatingService
import de.seuhd.campuscoffee.domain.ports.data.ActivityDataService
import de.seuhd.campuscoffee.domain.ports.data.BalanceLockService
import de.seuhd.campuscoffee.domain.ports.data.CoffeeRatingDataService
import de.seuhd.campuscoffee.domain.ports.data.UserDataService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

/**
 * Domain implementation of [CoffeeRatingService]. A vote is accepted only while the user has a cancellable
 * cup within its grace window; within that window a repeat call updates the same vote rather than adding
 * another (the current vote is the user's rating whose `createdAt` is at or after the window start). The
 * one-vote-per-window write is serialized per user by the shared advisory lock so a concurrent double-submit
 * cannot create two votes. Undoing the cup deletes that window's vote (via [clearVoteInWindow], called by the
 * consumption service). No per-cup or event-store identity is stored on a rating.
 */
@Service
class CoffeeRatingServiceImpl(
    private val coffeeRatingDataService: CoffeeRatingDataService,
    private val coffeeBeanService: CoffeeBeanService,
    private val activityDataService: ActivityDataService,
    private val userDataService: UserDataService,
    private val consumptionProperties: ConsumptionProperties,
    private val balanceLock: BalanceLockService
) : CoffeeRatingService {
    @Transactional
    override fun rateCurrentBean(
        userId: UUID,
        beanId: UUID,
        value: Int,
        actingUser: User
    ): CoffeeRating {
        requireMayRate(userId, actingUser)
        if (value !in MIN_RATING..MAX_RATING) {
            throw ValidationException("A rating must be from $MIN_RATING to $MAX_RATING.")
        }
        // Take the per-user lock BEFORE deriving the window, so this serializes with the undo path (which
        // holds the same lock through its decrement and vote clear). Deriving the window first would let a
        // concurrent undo commit in between and leave this write voting against the just-undone cup. Under
        // the lock the window is read consistently, and the same lock also makes the one-vote-per-window
        // check-then-write atomic so two concurrent submits cannot both insert.
        balanceLock.lockUser(userId)
        val windowStart = currentWindowStart(userId)
        val bean = canonicalBean(beanId)
        val existing = coffeeRatingDataService.findCurrentWindowVote(userId, windowStart)
        return if (existing != null) {
            coffeeRatingDataService.upsert(existing.copy(bean = bean, value = value))
        } else {
            // the vote belongs to the drinker (the owner of the cup), even when an admin casts it on their behalf
            coffeeRatingDataService.upsert(CoffeeRating(user = ownerOf(userId, actingUser), bean = bean, value = value))
        }
    }

    @Transactional
    override fun clearVoteInWindow(
        userId: UUID,
        windowStart: LocalDateTime
    ) {
        val vote = coffeeRatingDataService.findCurrentWindowVote(userId, windowStart) ?: return
        coffeeRatingDataService.delete(vote.persistedId)
    }

    override fun promptFor(
        userId: UUID,
        cancellableIncrement: CancellableIncrement?
    ): CoffeeRatingPrompt {
        // no cancellable cup: the prompt is hidden, so skip the default-bean lookup entirely
        if (cancellableIncrement == null) {
            return CoffeeRatingPrompt(canRate = false, defaultBeanId = null, value = null)
        }
        val currentVote = coffeeRatingDataService.findCurrentWindowVote(userId, cancellableIncrement.createdAt)
        val defaultBeanId = currentVote?.bean?.id ?: coffeeBeanService.mostRecentlyPurchased()?.id
        return CoffeeRatingPrompt(canRate = true, defaultBeanId = defaultBeanId, value = currentVote?.value)
    }

    override fun clear() = coffeeRatingDataService.clear()

    /** The current cup window's start, or a [ConflictException] when there is no cancellable cup to rate. */
    private fun currentWindowStart(userId: UUID): LocalDateTime {
        val ownerLogin = userDataService.getById(userId).loginName
        val candidate =
            activityDataService.lastCancellableIncrement(userId, ownerLogin)
                ?: throw ConflictException("There is no recent coffee to rate.")
        if (candidate.createdAt.isBefore(graceCutoff())) {
            throw ConflictException("The time to rate this coffee has passed.")
        }
        return candidate.createdAt
    }

    /** Resolves a bean id to its canonical (un-merged) bean, following a merge tombstone. */
    private fun canonicalBean(beanId: UUID): CoffeeBean {
        val bean = coffeeBeanService.getById(beanId)
        val mergedInto = bean.mergedIntoId ?: return bean
        return coffeeBeanService.getById(mergedInto)
    }

    /**
     * Allows a rating only when [actingUser] owns the cup (the drinker) or is an admin acting on the owner's
     * behalf, and, for the owner, only while the account is active (a deactivated user is read-only). This
     * mirrors the self-or-admin rule the consumption service uses for a `+1`/undo.
     */
    private fun requireMayRate(
        userId: UUID,
        actingUser: User
    ) {
        val isAdmin = actingUser.role == Role.ADMIN
        if (!isAdmin && actingUser.persistedId != userId) {
            throw ForbiddenException("A coffee may be rated only by the user who drank it or an admin.")
        }
        if (!isAdmin && actingUser.active != true) {
            throw ForbiddenException("A deactivated user is read-only and cannot rate a coffee.")
        }
    }

    /** The owner of the cup being rated: the acting user when they rate their own, else the resolved user. */
    private fun ownerOf(
        userId: UUID,
        actingUser: User
    ): User = if (actingUser.persistedId == userId) actingUser else userDataService.getById(userId)

    /** The cutoff time before which a coffee can no longer be rated (now minus the grace period). */
    private fun graceCutoff(): LocalDateTime =
        LocalDateTime.now(ZoneId.of("UTC")).minus(consumptionProperties.cancelGracePeriod)

    private companion object {
        private const val MIN_RATING = 1
        private const val MAX_RATING = 5
    }
}
