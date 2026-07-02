package de.seuhd.campuscoffee.data.implementations

import de.seuhd.campuscoffee.data.persistence.events.EventSourcedWriter
import de.seuhd.campuscoffee.domain.model.CoffeeBean
import de.seuhd.campuscoffee.domain.ports.data.CoffeeBeanDataService
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Event-sourced coffee-bean data adapter (the `@Primary` persistence path): a Decorator around the
 * relational [CoffeeBeanDataServiceImpl], pinned to it by `@Qualifier(BEAN_NAME)`. Reads and the
 * `findActiveByName` lookup auto-delegate; each write is recorded as a full-state event and projected into
 * the read table in one transaction. A bean has no user reference, so its DELETE event carries no owner key.
 */
@Service
@Primary
class EventSourcedCoffeeBeanDataService(
    @param:Qualifier(CoffeeBeanDataServiceImpl.BEAN_NAME) private val delegate: CoffeeBeanDataService,
    private val writer: EventSourcedWriter
) : CoffeeBeanDataService by delegate {
    @Transactional
    override fun upsert(domain: CoffeeBean): CoffeeBean =
        writer.upsert(
            domain,
            delegate::getById,
            { id, now -> domain.copy(id = id, createdAt = now, updatedAt = now) },
            { existing, now -> domain.copy(createdAt = existing.createdAt, updatedAt = now) }
        )

    @Transactional
    override fun delete(id: UUID) = writer.delete(CoffeeBean::class, id, delegate::getById)

    @Transactional
    override fun clear() = writer.clear(CoffeeBean::class, delegate::clear)
}
