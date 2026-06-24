package de.seuhd.campuscoffee.domain.ports.data

import de.seuhd.campuscoffee.domain.model.Expense
import java.util.UUID

/**
 * Port interface for expense data operations, implemented by the data layer. Extends the generic
 * [CrudDataService] and adds the per-buyer lookup the balance and activity rely on.
 */
interface ExpenseDataService : CrudDataService<Expense, UUID> {
    /**
     * Returns all expenses recorded with the given user as their buyer, in no particular order.
     *
     * @param userId the id of the buyer whose expenses to return
     * @return that buyer's expenses; never null, but may be empty
     */
    fun getAllByBuyer(userId: UUID): List<Expense>
}
