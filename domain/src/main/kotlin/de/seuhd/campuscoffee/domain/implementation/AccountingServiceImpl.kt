package de.seuhd.campuscoffee.domain.implementation

import de.seuhd.campuscoffee.domain.exceptions.ForbiddenException
import de.seuhd.campuscoffee.domain.model.ActivityEntry
import de.seuhd.campuscoffee.domain.model.Role
import de.seuhd.campuscoffee.domain.model.User
import de.seuhd.campuscoffee.domain.model.UserBalance
import de.seuhd.campuscoffee.domain.model.UserSummary
import de.seuhd.campuscoffee.domain.model.persistedId
import de.seuhd.campuscoffee.domain.ports.api.AccountingService
import de.seuhd.campuscoffee.domain.ports.api.CoffeeConsumptionService
import de.seuhd.campuscoffee.domain.ports.api.CoffeePriceService
import de.seuhd.campuscoffee.domain.ports.data.ActivityDataService
import de.seuhd.campuscoffee.domain.ports.data.CoffeeConsumptionDataService
import de.seuhd.campuscoffee.domain.ports.data.UserDataService
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Domain implementation of [AccountingService]. Composes the price, consumption, expense, and payment data
 * (via the event-log-backed [ActivityDataService]) into the read-side money views. A member's balance and
 * activity are readable by the member or an admin; the kitty balance is readable by any member; the kitty
 * history and the all-member overview are admin-only.
 */
@Service
class AccountingServiceImpl(
    private val activityDataService: ActivityDataService,
    private val coffeePriceService: CoffeePriceService,
    private val coffeeConsumptionDataService: CoffeeConsumptionDataService,
    private val coffeeConsumptionService: CoffeeConsumptionService,
    private val userDataService: UserDataService
) : AccountingService {
    override fun userSummary(
        userId: UUID,
        activityLimit: Int,
        activityOffset: Int,
        actingUser: User
    ): UserSummary {
        val user = requireMayViewUser(userId, actingUser)
        val fullActivity = activityDataService.userActivity(userId, user.loginName)
        val count = coffeeConsumptionDataService.getByUserId(userId).count
        return UserSummary(
            count = count,
            priceCents = coffeePriceService.getCurrent().amountCents,
            balanceCents = fullActivity.lastOrNull()?.runningBalanceCents ?: 0L,
            kittyBalanceCents = kittyBalanceCents(),
            // only offer undo when there is actually a coffee to remove (a stale stack entry could otherwise
            // mark a zero count cancellable)
            cancellable = count > 0 && coffeeConsumptionService.cancellableIncrement(userId, actingUser) != null,
            // the summary is the member-serving view: strip the kitty split so it never reaches a member
            activity = page(fullActivity, activityLimit, activityOffset).map { it.withoutKittyPortion() }
        )
    }

    override fun userActivity(
        userId: UUID,
        limit: Int,
        offset: Int,
        actingUser: User,
        includeKittyPortion: Boolean
    ): List<ActivityEntry> {
        val user = requireMayViewUser(userId, actingUser)
        val page = page(activityDataService.userActivity(userId, user.loginName), limit, offset)
        // the admin-by-id read keeps the kitty split; the member-serving read strips it so the kitty-funded
        // portion of a split purchase never reaches a member (the balance math is unaffected either way)
        return if (includeKittyPortion) page else page.map { it.withoutKittyPortion() }
    }

    override fun kittyBalanceCents(): Long = activityDataService.kittyHistory().lastOrNull()?.runningBalanceCents ?: 0L

    override fun kittyHistory(
        limit: Int,
        offset: Int,
        actingUser: User
    ): List<ActivityEntry> {
        requireAdmin(actingUser)
        return page(activityDataService.kittyHistory(), limit, offset)
    }

    override fun allBalances(actingUser: User): List<UserBalance> {
        requireAdmin(actingUser)
        // sort by login name so the per-member overview keeps a stable, human-readable order; a mutation
        // (a deactivation, a token rotation) must never reshuffle the admin's overview
        return userDataService
            .getAll()
            .sortedBy { it.loginName }
            .map { user ->
                // isolate per-member failures: one member's malformed stream must not 500 the whole overview,
                // so a failed balance walk falls back to 0 for that member rather than failing every row
                val balance =
                    runCatching {
                        activityDataService
                            .userActivity(user.persistedId, user.loginName)
                            .lastOrNull()
                            ?.runningBalanceCents ?: 0L
                    }.getOrDefault(0L)
                UserBalance(user, coffeeConsumptionDataService.getByUserId(user.persistedId).count, balance)
            }
    }

    /**
     * Strips both portions of a split expense from an activity entry (the member-serving views): the split is
     * admin-only and must never reach a member. A no-op for an entry that carries none.
     */
    private fun ActivityEntry.withoutKittyPortion(): ActivityEntry =
        if (kittyAmountCents == null && privateAmountCents == null) {
            this
        } else {
            copy(privateAmountCents = null, kittyAmountCents = null)
        }

    /** Reverses the oldest-first activity to newest-first and returns the requested page. */
    private fun page(
        activity: List<ActivityEntry>,
        limit: Int,
        offset: Int
    ): List<ActivityEntry> = activity.asReversed().drop(offset.coerceAtLeast(0)).take(limit.coerceIn(0, MAX_LIMIT))

    /** Resolves the member (404 if missing) and allows the read only for the member themselves or an admin. */
    private fun requireMayViewUser(
        userId: UUID,
        actingUser: User
    ): User {
        val user = userDataService.getById(userId)
        if (actingUser.role != Role.ADMIN && actingUser.persistedId != userId) {
            throw ForbiddenException("A member's balance may be read only by the member themselves or an admin.")
        }
        return user
    }

    /** Requires [actingUser] to be an admin, else 403. */
    private fun requireAdmin(actingUser: User) {
        if (actingUser.role != Role.ADMIN) {
            throw ForbiddenException("Only an admin may read this.")
        }
    }

    private companion object {
        private const val MAX_LIMIT = 100
    }
}
