package de.seuhd.campuscoffee.domain.ports.data

import de.seuhd.campuscoffee.domain.model.Payment
import java.util.UUID

/**
 * Port interface for payment data operations, implemented by the data layer. Extends the generic
 * [CrudDataService] and adds the per-member lookup the balance and activity rely on.
 */
interface PaymentDataService : CrudDataService<Payment, UUID> {
    /**
     * Returns all deposits recorded for the given member (payments whose user is that member), in no
     * particular order. Pure kitty adjustments (payments with no user) are not returned by this lookup.
     *
     * @param userId the id of the member whose deposits to return
     * @return that member's deposits; never null, but may be empty
     */
    fun getAllByUser(userId: UUID): List<Payment>
}
