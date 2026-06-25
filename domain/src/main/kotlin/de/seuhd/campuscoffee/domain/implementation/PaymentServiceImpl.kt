package de.seuhd.campuscoffee.domain.implementation

import de.seuhd.campuscoffee.domain.exceptions.ConflictException
import de.seuhd.campuscoffee.domain.exceptions.ForbiddenException
import de.seuhd.campuscoffee.domain.exceptions.ValidationException
import de.seuhd.campuscoffee.domain.model.Payment
import de.seuhd.campuscoffee.domain.model.Role
import de.seuhd.campuscoffee.domain.model.User
import de.seuhd.campuscoffee.domain.ports.api.PaymentService
import de.seuhd.campuscoffee.domain.ports.data.BalanceDataService
import de.seuhd.campuscoffee.domain.ports.data.BalanceLock
import de.seuhd.campuscoffee.domain.ports.data.PaymentDataService
import de.seuhd.campuscoffee.domain.ports.data.UserDataService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Domain implementation of [PaymentService]. Every operation is admin-only. A deposit records a member
 * paying money in (positive only); a kitty adjustment changes the kitty alone and may be signed, but may not
 * drive the kitty balance below zero (409). Payments are never edited. A mistake is corrected with a
 * compensating entry.
 */
@Service
class PaymentServiceImpl(
    private val paymentDataService: PaymentDataService,
    private val userDataService: UserDataService,
    private val balanceDataService: BalanceDataService,
    private val balanceLock: BalanceLock
) : PaymentService {
    @Transactional
    override fun recordDeposit(
        userId: UUID,
        amountCents: Int,
        note: String?,
        actingUser: User
    ): Payment {
        requireAdmin(actingUser)
        if (amountCents <= 0) {
            throw ValidationException("A deposit amount must be positive.")
        }
        // no explicit kitty lock here: a deposit is always positive and cannot overdraw, so it has no
        // overdraw check to serialize. Its kitty recompute is still serialized against concurrent kitty
        // writers by the lock the projection takes around every recompute (see BalanceProjection.maintain).
        val user = userDataService.getById(userId)
        return paymentDataService.upsert(Payment(user = user, amountCents = amountCents, note = note))
    }

    @Transactional
    override fun adjustKitty(
        amountCents: Int,
        note: String?,
        actingUser: User
    ): Payment {
        requireAdmin(actingUser)
        if (amountCents == 0) {
            throw ValidationException("A kitty adjustment amount must not be zero.")
        }
        // serialize against other kitty writes so two concurrent ops cannot both read the same balance,
        // both pass the check, and both commit; the kitty is an aggregate across rows that @Version cannot guard
        balanceLock.lockKitty()
        if (kittyBalanceCents() + amountCents < 0) {
            throw ConflictException("This adjustment would make the kitty balance negative.")
        }
        return paymentDataService.upsert(Payment(user = null, amountCents = amountCents, note = note))
    }

    override fun clear() = paymentDataService.clear()

    /** The current kitty balance in cents, read O(1) from the maintained projection (held under the lock). */
    private fun kittyBalanceCents(): Long = balanceDataService.kittyBalanceCents()

    /** Requires [actingUser] to be an admin, else 403. */
    private fun requireAdmin(actingUser: User) {
        if (actingUser.role != Role.ADMIN) {
            throw ForbiddenException("Only an admin may manage the kitty.")
        }
    }
}
