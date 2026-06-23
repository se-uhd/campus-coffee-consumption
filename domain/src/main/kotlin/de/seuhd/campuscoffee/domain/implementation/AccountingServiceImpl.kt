package de.seuhd.campuscoffee.domain.implementation

import de.seuhd.campuscoffee.domain.exceptions.ForbiddenException
import de.seuhd.campuscoffee.domain.model.LedgerEntry
import de.seuhd.campuscoffee.domain.model.MemberBalance
import de.seuhd.campuscoffee.domain.model.MemberSummary
import de.seuhd.campuscoffee.domain.model.Role
import de.seuhd.campuscoffee.domain.model.User
import de.seuhd.campuscoffee.domain.model.persistedId
import de.seuhd.campuscoffee.domain.ports.api.AccountingService
import de.seuhd.campuscoffee.domain.ports.api.CoffeeConsumptionService
import de.seuhd.campuscoffee.domain.ports.api.CoffeePriceService
import de.seuhd.campuscoffee.domain.ports.data.CoffeeConsumptionDataService
import de.seuhd.campuscoffee.domain.ports.data.LedgerDataService
import de.seuhd.campuscoffee.domain.ports.data.UserDataService
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Domain implementation of [AccountingService]. Composes the price, consumption, expense, and payment data
 * (via the event-log-backed [LedgerDataService]) into the read-side money views. A member's balance and
 * ledger are readable by the member or an admin; the kitty balance is readable by any member; the kitty
 * ledger and the all-member overview are admin-only.
 */
@Service
class AccountingServiceImpl(
    private val ledgerDataService: LedgerDataService,
    private val coffeePriceService: CoffeePriceService,
    private val coffeeConsumptionDataService: CoffeeConsumptionDataService,
    private val coffeeConsumptionService: CoffeeConsumptionService,
    private val userDataService: UserDataService
) : AccountingService {
    override fun memberSummary(
        userId: UUID,
        ledgerLimit: Int,
        ledgerOffset: Int,
        actingUser: User
    ): MemberSummary {
        val member = requireMayViewMember(userId, actingUser)
        val fullLedger = ledgerDataService.memberLedger(userId, member.loginName)
        val count = coffeeConsumptionDataService.getByUserId(userId).count
        return MemberSummary(
            count = count,
            priceCents = coffeePriceService.getCurrent().amountCents,
            balanceCents = fullLedger.lastOrNull()?.runningBalanceCents ?: 0L,
            kittyBalanceCents = kittyBalanceCents(),
            // only offer undo when there is actually a coffee to remove (a stale stack entry could otherwise
            // mark a zero count cancellable)
            cancellable = count > 0 && coffeeConsumptionService.cancellableIncrement(userId, actingUser) != null,
            // the summary is the member-serving view: strip the kitty split so it never reaches a member
            ledger = page(fullLedger, ledgerLimit, ledgerOffset).map { it.withoutKittyPortion() }
        )
    }

    override fun memberLedger(
        userId: UUID,
        limit: Int,
        offset: Int,
        actingUser: User,
        includeKittyPortion: Boolean
    ): List<LedgerEntry> {
        val member = requireMayViewMember(userId, actingUser)
        val page = page(ledgerDataService.memberLedger(userId, member.loginName), limit, offset)
        // the admin-by-id read keeps the kitty split; the member-serving read strips it so the kitty-funded
        // portion of a split purchase never reaches a member (the balance math is unaffected either way)
        return if (includeKittyPortion) page else page.map { it.withoutKittyPortion() }
    }

    override fun kittyBalanceCents(): Long = ledgerDataService.kittyLedger().lastOrNull()?.runningBalanceCents ?: 0L

    override fun kittyLedger(
        limit: Int,
        offset: Int,
        actingUser: User
    ): List<LedgerEntry> {
        requireAdmin(actingUser)
        return page(ledgerDataService.kittyLedger(), limit, offset)
    }

    override fun allBalances(actingUser: User): List<MemberBalance> {
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
                        ledgerDataService
                            .memberLedger(user.persistedId, user.loginName)
                            .lastOrNull()
                            ?.runningBalanceCents ?: 0L
                    }.getOrDefault(0L)
                MemberBalance(user, coffeeConsumptionDataService.getByUserId(user.persistedId).count, balance)
            }
    }

    /**
     * Strips both portions of a split expense from a ledger entry (the member-serving views): the split is
     * admin-only and must never reach a member. A no-op for an entry that carries none.
     */
    private fun LedgerEntry.withoutKittyPortion(): LedgerEntry =
        if (kittyAmountCents == null && privateAmountCents == null) {
            this
        } else {
            copy(privateAmountCents = null, kittyAmountCents = null)
        }

    /** Reverses the oldest-first ledger to newest-first and returns the requested page. */
    private fun page(
        ledger: List<LedgerEntry>,
        limit: Int,
        offset: Int
    ): List<LedgerEntry> = ledger.asReversed().drop(offset.coerceAtLeast(0)).take(limit.coerceIn(0, MAX_LIMIT))

    /** Resolves the member (404 if missing) and allows the read only for the member themselves or an admin. */
    private fun requireMayViewMember(
        userId: UUID,
        actingUser: User
    ): User {
        val member = userDataService.getById(userId)
        if (actingUser.role != Role.ADMIN && actingUser.persistedId != userId) {
            throw ForbiddenException("A member's balance may be read only by the member themselves or an admin.")
        }
        return member
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
