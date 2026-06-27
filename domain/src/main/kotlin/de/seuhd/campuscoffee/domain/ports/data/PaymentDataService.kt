package de.seuhd.campuscoffee.domain.ports.data

import de.seuhd.campuscoffee.domain.model.Payment
import java.util.UUID

/**
 * Port interface for payment data operations, implemented by the data layer. Extends the generic
 * [CrudDataService] and adds the per-user lookup the balance and activity rely on.
 */
interface PaymentDataService : CrudDataService<Payment, UUID> {
    /**
     * Returns all deposits recorded for the given user (payments whose user is that user), in no
     * particular order. Pure kitty adjustments (payments with no user) are not returned by this lookup.
     *
     * @param userId the id of the user whose deposits to return
     * @return that user's deposits; never null, but may be empty
     */
    fun getAllByUser(userId: UUID): List<Payment>
}
