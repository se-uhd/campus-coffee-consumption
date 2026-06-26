package de.seuhd.campuscoffee.domain.implementation

import de.seuhd.campuscoffee.domain.model.User
import de.seuhd.campuscoffee.domain.model.UserBalance
import de.seuhd.campuscoffee.domain.model.UserSummary
import de.seuhd.campuscoffee.domain.model.persistedId
import de.seuhd.campuscoffee.domain.ports.api.AccountingService
import de.seuhd.campuscoffee.domain.ports.api.CoffeeConsumptionService
import de.seuhd.campuscoffee.domain.ports.api.CoffeePriceService
import de.seuhd.campuscoffee.domain.ports.data.ActivityDataService
import de.seuhd.campuscoffee.domain.ports.data.BalanceDataService
import de.seuhd.campuscoffee.domain.ports.data.CoffeeConsumptionDataService
import de.seuhd.campuscoffee.domain.ports.data.UserDataService
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Domain implementation of [AccountingService]. Composes the price, consumption, and balance data into the
 * read-side money numbers: a member's landing summary, the kitty balance, and the all-member overview. The
 * chronological feeds live on [ActivityServiceImpl]; this service keeps the summary self-contained, reading
 * the full member walk once (via [ActivityDataService]) so the headline balance is the true current balance
 * and not the running balance of some paged slice.
 */
@Service
class AccountingServiceImpl(
    private val activityDataService: ActivityDataService,
    private val balanceDataService: BalanceDataService,
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
        val user = requireMayViewUser(userId, actingUser, userDataService)
        // read the full oldest-first walk once: its last entry's running balance is the member's current
        // balance, and a page of it is the landing activity. Deriving the balance from a paged, newest-first
        // slice would instead report the running balance of whatever entry happened to land last in that page.
        val fullActivity = activityDataService.userActivity(userId, user.loginName)
        val count = coffeeConsumptionDataService.getByUserId(userId).count
        return UserSummary(
            count = count,
            priceCents = coffeePriceService.getCurrent().amountCents,
            balanceCents = fullActivity.lastOrNull()?.runningBalanceCents ?: 0L,
            // the kitty balance is a single maintained number, so reading it never replays the global money
            // stream the way every member landing load used to
            kittyBalanceCents = balanceDataService.kittyBalanceCents(),
            // only offer undo when there is actually a coffee to remove (a stale stack entry could otherwise
            // mark a zero count cancellable)
            cancellable = count > 0 && coffeeConsumptionService.cancellableIncrement(userId, actingUser) != null,
            // the summary is the member-serving view: strip the kitty split so it never reaches a member
            activity =
                pageNewestFirst(fullActivity, activityLimit, activityOffset)
                    .map { it.withoutKittyPortion() }
        )
    }

    override fun kittyBalanceCents(): Long = balanceDataService.kittyBalanceCents()

    override fun allBalances(actingUser: User): List<UserBalance> {
        requireAdmin(actingUser)
        // read every member's balance from the maintained projection in one query instead of replaying each
        // member's whole stream; a member with no recorded activity is absent from the map and defaults to 0
        val balances = balanceDataService.allMemberBalancesCents()
        // sort by login name so the per-member overview keeps a stable, human-readable order; a mutation
        // (a deactivation, a token rotation) must never reshuffle the admin's overview
        return userDataService
            .getAll()
            .sortedBy { it.loginName }
            .map { user ->
                val count = coffeeConsumptionDataService.getByUserId(user.persistedId).count
                UserBalance(user, count, balances[user.persistedId] ?: 0L)
            }
    }
}
