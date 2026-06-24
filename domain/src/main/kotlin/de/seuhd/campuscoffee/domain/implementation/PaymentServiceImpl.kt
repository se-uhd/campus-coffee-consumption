package de.seuhd.campuscoffee.domain.implementation

import de.seuhd.campuscoffee.domain.exceptions.ConflictException
import de.seuhd.campuscoffee.domain.exceptions.ForbiddenException
import de.seuhd.campuscoffee.domain.exceptions.ValidationException
import de.seuhd.campuscoffee.domain.model.Payment
import de.seuhd.campuscoffee.domain.model.Role
import de.seuhd.campuscoffee.domain.model.User
import de.seuhd.campuscoffee.domain.ports.api.PaymentService
import de.seuhd.campuscoffee.domain.ports.data.ActivityDataService
import de.seuhd.campuscoffee.domain.ports.data.KittyLock
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
    private val activityDataService: ActivityDataService,
    private val kittyLock: KittyLock
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
        kittyLock.lockForUpdate()
        if (kittyBalanceCents() + amountCents < 0) {
            throw ConflictException("This adjustment would make the kitty balance negative.")
        }
        return paymentDataService.upsert(Payment(user = null, amountCents = amountCents, note = note))
    }

    override fun clear() = paymentDataService.clear()

    /** The current kitty balance in cents, read from the event log (the last running balance of its history). */
    private fun kittyBalanceCents(): Long = activityDataService.kittyHistory().lastOrNull()?.runningBalanceCents ?: 0L

    /** Requires [actingUser] to be an admin, else 403. */
    private fun requireAdmin(actingUser: User) {
        if (actingUser.role != Role.ADMIN) {
            throw ForbiddenException("Only an admin may manage the kitty.")
        }
    }
}
