package de.seuhd.campuscoffee.domain.implementation

import de.seuhd.campuscoffee.domain.exceptions.ForbiddenException
import de.seuhd.campuscoffee.domain.exceptions.ValidationException
import de.seuhd.campuscoffee.domain.model.Payment
import de.seuhd.campuscoffee.domain.model.Role
import de.seuhd.campuscoffee.domain.model.User
import de.seuhd.campuscoffee.domain.ports.api.PaymentService
import de.seuhd.campuscoffee.domain.ports.data.PaymentDataService
import de.seuhd.campuscoffee.domain.ports.data.UserDataService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Domain implementation of [PaymentService]. Every operation is admin-only. A settlement records a member
 * paying money in (positive only); a kitty adjustment changes the kitty alone and may be signed. Payments
 * are never edited — a mistake is corrected with a compensating entry.
 */
@Service
class PaymentServiceImpl(
    private val paymentDataService: PaymentDataService,
    private val userDataService: UserDataService
) : PaymentService {
    @Transactional
    override fun recordSettlement(
        userId: UUID,
        amountCents: Int,
        note: String?,
        actingUser: User
    ): Payment {
        requireAdmin(actingUser)
        if (amountCents <= 0) {
            throw ValidationException("A settlement amount must be positive.")
        }
        val member = userDataService.getById(userId)
        return paymentDataService.upsert(Payment(user = member, amountCents = amountCents, note = note))
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
        return paymentDataService.upsert(Payment(user = null, amountCents = amountCents, note = note))
    }

    override fun clear() = paymentDataService.clear()

    /** Requires [actingUser] to be an admin, else 403. */
    private fun requireAdmin(actingUser: User) {
        if (actingUser.role != Role.ADMIN) {
            throw ForbiddenException("Only an admin may manage the kitty.")
        }
    }
}
