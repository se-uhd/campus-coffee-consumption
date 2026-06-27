package de.seuhd.campuscoffee.data.persistence.eventsourcing

import de.seuhd.campuscoffee.data.implementations.ExpenseDataServiceImpl
import de.seuhd.campuscoffee.domain.model.Expense
import de.seuhd.campuscoffee.domain.ports.data.ExpenseDataService
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Event sourcing expense data adapter, the only persistence path. A Decorator around the relational
 * [ExpenseDataServiceImpl] (both adapters for the same `ExpenseDataService` port, so it is `@Primary`),
 * delegating reads and `getAllByBuyer` and writing each recorded purchase and each admin correction
 * event-first.
 */
@Service
@Primary
class EventSourcedExpenseDataService(
    private val delegate: ExpenseDataServiceImpl,
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
