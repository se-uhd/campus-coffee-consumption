package de.seuhd.campuscoffee.data.implementations

import de.seuhd.campuscoffee.data.persistence.events.EventSourcedWriter
import de.seuhd.campuscoffee.domain.model.CoffeeRating
import de.seuhd.campuscoffee.domain.ports.data.CoffeeRatingDataService
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Event-sourced coffee-rating data adapter (the `@Primary` persistence path): a Decorator around the
 * relational [CoffeeRatingDataServiceImpl], pinned to it by `@Qualifier(BEAN_NAME)`. Reads and the
 * current-window lookup auto-delegate; each write is recorded as a full-state event and projected in one
 * transaction. A rating moves no balance, so its DELETE event carries no owner key.
 */
@Service
@Primary
class EventSourcedCoffeeRatingDataService(
    @param:Qualifier(CoffeeRatingDataServiceImpl.BEAN_NAME) private val delegate: CoffeeRatingDataService,
    private val writer: EventSourcedWriter
) : CoffeeRatingDataService by delegate {
    @Transactional
    override fun upsert(domain: CoffeeRating): CoffeeRating =
        writer.upsert(
            domain,
            delegate::getById,
            { id, now -> domain.copy(id = id, createdAt = now, updatedAt = now) },
            { existing, now -> domain.copy(createdAt = existing.createdAt, updatedAt = now) }
        )

    @Transactional
    override fun delete(id: UUID) = writer.delete(CoffeeRating::class, id, delegate::getById)

    @Transactional
    override fun clear() = writer.clear(CoffeeRating::class, delegate::clear)
}
