package de.seuhd.campuscoffee.data.implementations
import de.seuhd.campuscoffee.data.persistence.events.EventSourcedWriter
import de.seuhd.campuscoffee.domain.model.Expense
import de.seuhd.campuscoffee.domain.ports.data.ExpenseDataService
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Event sourcing expense data adapter, the only persistence path. A Decorator around the relational
 * [ExpenseDataServiceImpl] (both adapters for the same `ExpenseDataService` port, so it is `@Primary`). The
 * `delegate` is typed against the port and pinned to the relational bean with
 * `@Qualifier(ExpenseDataServiceImpl.BEAN_NAME)`, so the wrapper shares only the interface with the wrappee.
 * It delegates reads and `getAllByBuyer` and writes each recorded purchase and each admin correction
 * event-first.
 */
@Service
@Primary
class EventSourcedExpenseDataService(
    @param:Qualifier(ExpenseDataServiceImpl.BEAN_NAME) private val delegate: ExpenseDataService,
    private val writer: EventSourcedWriter
) : ExpenseDataService by delegate {
    @Transactional
    override fun upsert(domain: Expense): Expense =
        writer.upsert(
            domain,
            delegate::getById,
            { id, now -> domain.copy(id = id, createdAt = now, updatedAt = now) },
            { existing, now -> domain.copy(createdAt = existing.createdAt, updatedAt = now) }
        )

    @Transactional
    override fun delete(id: UUID) =
        // carry the buyer id on the DELETE event so the user activity still matches it and reverses the credit
        writer.delete(Expense::class, id, delegate::getById) { mapOf("buyerUserId" to it.buyer.id) }

    @Transactional
    override fun clear() = writer.clear(Expense::class, delegate::clear)
}
