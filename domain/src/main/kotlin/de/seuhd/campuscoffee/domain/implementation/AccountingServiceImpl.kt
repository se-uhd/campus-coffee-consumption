package de.seuhd.campuscoffee.domain.implementation

import de.seuhd.campuscoffee.domain.model.ActivityEntry
import de.seuhd.campuscoffee.domain.model.ActivityEntryType
import de.seuhd.campuscoffee.domain.model.SummaryPanel
import de.seuhd.campuscoffee.domain.model.User
import de.seuhd.campuscoffee.domain.model.UserBalance
import de.seuhd.campuscoffee.domain.model.UserSummary
import de.seuhd.campuscoffee.domain.model.persistedId
import de.seuhd.campuscoffee.domain.ports.api.AccountingService
import de.seuhd.campuscoffee.domain.ports.api.CoffeeConsumptionService
import de.seuhd.campuscoffee.domain.ports.api.CoffeePriceService
import de.seuhd.campuscoffee.domain.ports.api.CoffeeRatingService
import de.seuhd.campuscoffee.domain.ports.data.ActivityDataService
import de.seuhd.campuscoffee.domain.ports.data.BalanceDataService
import de.seuhd.campuscoffee.domain.ports.data.CoffeeConsumptionDataService
import de.seuhd.campuscoffee.domain.ports.data.UserDataService
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.TemporalAdjusters
import java.util.UUID

/**
 * Domain implementation of [AccountingService]. Composes the price, consumption, and balance data into the
 * read-side money numbers: a user's landing summary, the kitty balance, and the all-user overview. The
 * chronological feeds live on [ActivityServiceImpl]; this service keeps the summary self-contained, reading
 * the full user walk once (via [ActivityDataService]) so the headline balance is the true current balance
 * and not the running balance of some paged slice.
 */
@Service
class AccountingServiceImpl(
    private val activityDataService: ActivityDataService,
    private val balanceDataService: BalanceDataService,
    private val coffeePriceService: CoffeePriceService,
    private val coffeeConsumptionDataService: CoffeeConsumptionDataService,
    private val coffeeConsumptionService: CoffeeConsumptionService,
    private val coffeeRatingService: CoffeeRatingService,
    private val userDataService: UserDataService,
    private val clock: Clock,
    private val summaryProperties: SummaryProperties
) : AccountingService {
    override fun userSummary(
        userId: UUID,
        activityLimit: Int,
        activityOffset: Int,
        actingUser: User,
        includeKittyPortion: Boolean
    ): UserSummary {
        val user = requireMayViewUser(userId, actingUser, userDataService)
        // read the full oldest-first walk once: its last entry's running balance is the user's current
        // balance, and a page of it is the landing activity. Deriving the balance from a paged, newest-first
        // slice would instead report the running balance of whatever entry happened to land last in that page.
        val fullActivity = activityDataService.userActivity(userId, user.loginName)
        val count = coffeeConsumptionDataService.getByUserId(userId).count
        val cupStats = cupStats(fullActivity)
        // only offer undo/rating when there is actually a coffee to remove (a stale stack entry could
        // otherwise mark a zero count cancellable); reuse this increment for the rating prompt so it does
        // not re-walk the log
        val cancellableIncrement =
            if (count > 0) coffeeConsumptionService.cancellableIncrement(userId, actingUser) else null
        return UserSummary(
            count = count,
            priceCents = coffeePriceService.getCurrent().amountCents,
            balanceCents = fullActivity.lastOrNull()?.runningBalanceCents ?: 0L,
            // the kitty balance is a single maintained number, so reading it never replays the global money
            // stream the way every user landing load used to
            kittyBalanceCents = balanceDataService.kittyBalanceCents(),
            cancellable = cancellableIncrement != null,
            // the subject's own panel preference (defaults to the balance panel); the cup-stat fields below are
            // computed regardless so the landing can render either panel without a second read
            summaryPanel = user.summaryPanel ?: SummaryPanel.BALANCE,
            firstCupAt = cupStats.firstCupAt,
            cupsThisWeek = cupStats.cupsThisWeek,
            cupsToday = cupStats.cupsToday,
            ratingPrompt = coffeeRatingService.promptFor(userId, cancellableIncrement),
            // default (user-serving) view strips the kitty split so it never reaches a user; the admin
            // per-user view keeps it so the landing's first page matches its kitty-inclusive paged feed
            activity =
                pageNewestFirst(fullActivity, activityLimit, activityOffset)
                    .map { if (includeKittyPortion) it else it.withoutKittyPortion() }
        )
    }

    /**
     * The cup-stat windows for the landing's `CUPS` panel, derived from the same [fullActivity] walk that
     * computes the balance (so they cost no extra query). [CupStats.firstCupAt] is the user's first cup; the
     * today/week counts net the cup deltas since the start of the local day/week, clamped at 0 so an admin
     * down-correction never shows a negative figure.
     *
     * @param fullActivity the user's full oldest-first activity walk
     */
    private fun cupStats(fullActivity: List<ActivityEntry>): CupStats {
        val cupEntries =
            fullActivity.filter {
                it.type == ActivityEntryType.CONSUMPTION || it.type == ActivityEntryType.CONSUMPTION_CANCEL
            }
        return CupStats(
            firstCupAt = cupEntries.firstOrNull { (it.delta ?: 0) > 0 }?.createdAt,
            cupsThisWeek = cupsSince(cupEntries, startOfLocalWeekUtc()),
            cupsToday = cupsSince(cupEntries, startOfLocalDayUtc())
        )
    }

    /** The net cups (summed [ActivityEntry.delta]) among [cupEntries] at or after [cutoff], clamped at 0. */
    private fun cupsSince(
        cupEntries: List<ActivityEntry>,
        cutoff: LocalDateTime
    ): Int = cupEntries.filter { !it.createdAt.isBefore(cutoff) }.sumOf { it.delta ?: 0 }.coerceAtLeast(0)

    /** The current local day's midnight as a UTC `LocalDateTime` (the activity timestamps it gates are UTC). */
    private fun startOfLocalDayUtc(): LocalDateTime =
        clock
            .instant()
            .atZone(summaryProperties.timeZone)
            .toLocalDate()
            .atStartOfDay(summaryProperties.timeZone)
            .withZoneSameInstant(ZoneOffset.UTC)
            .toLocalDateTime()

    /** The most recent Monday 00:00 local as a UTC `LocalDateTime` (the activity timestamps it gates are UTC). */
    private fun startOfLocalWeekUtc(): LocalDateTime =
        clock
            .instant()
            .atZone(summaryProperties.timeZone)
            .toLocalDate()
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            .atStartOfDay(summaryProperties.timeZone)
            .withZoneSameInstant(ZoneOffset.UTC)
            .toLocalDateTime()

    override fun kittyBalanceCents(): Long = balanceDataService.kittyBalanceCents()

    override fun allBalances(actingUser: User): List<UserBalance> {
        requireAdmin(actingUser)
        // read every user's balance from the maintained projection in one query instead of replaying each
        // user's whole stream; a user with no recorded activity is absent from the map and defaults to 0
        val balances = balanceDataService.allUserBalancesCents()
        // sort by login name so the per-user overview keeps a stable, human-readable order; a mutation
        // (a deactivation, a token rotation) must never reshuffle the admin's overview
        return userDataService
            .getAll()
            .sortedBy { it.loginName }
            .map { user ->
                val count = coffeeConsumptionDataService.getByUserId(user.persistedId).count
                UserBalance(user, count, balances[user.persistedId] ?: 0L)
            }
    }

    /**
     * The cup-stat windows that back the landing's `CUPS` panel.
     *
     * @property firstCupAt the time of the user's first cup, or null if they have none
     * @property cupsThisWeek the net cups since the start of the local week (clamped at 0)
     * @property cupsToday the net cups since the start of the local day (clamped at 0)
     */
    private data class CupStats(
        val firstCupAt: LocalDateTime?,
        val cupsThisWeek: Int,
        val cupsToday: Int
    )
}
