package de.seuhd.campuscoffee.data.implementations
import de.seuhd.campuscoffee.data.persistence.events.EventSourcedWriter
import de.seuhd.campuscoffee.domain.model.Payment
import de.seuhd.campuscoffee.domain.ports.data.PaymentDataService
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Event sourcing payment data adapter, the only persistence path. A Decorator around the relational
 * [PaymentDataServiceImpl] (both adapters for the same `PaymentDataService` port, so it is `@Primary`). The
 * `delegate` is typed against the port and pinned to the relational bean with
 * `@Qualifier(PaymentDataServiceImpl.BEAN_NAME)`, so the wrapper shares only the interface with the wrappee.
 * It delegates reads and `getAllByUser` and writes each deposit and kitty adjustment event-first.
 */
@Service
@Primary
class EventSourcedPaymentDataService(
    @param:Qualifier(PaymentDataServiceImpl.BEAN_NAME) private val delegate: PaymentDataService,
    private val writer: EventSourcedWriter
) : PaymentDataService by delegate {
    @Transactional
    override fun upsert(domain: Payment): Payment =
        writer.upsert(
            domain,
            delegate::getById,
            { id, now -> domain.copy(id = id, createdAt = now, updatedAt = now) },
            { existing, now -> domain.copy(createdAt = existing.createdAt, updatedAt = now) }
        )

    @Transactional
    override fun delete(id: UUID) =
        // carry the user id (null for a pure kitty adjustment) so the user activity reverses a deposit
        writer.delete(Payment::class, id, delegate::getById) { mapOf("userId" to it.user?.id) }

    @Transactional
    override fun clear() = writer.clear(Payment::class, delegate::clear)
}
