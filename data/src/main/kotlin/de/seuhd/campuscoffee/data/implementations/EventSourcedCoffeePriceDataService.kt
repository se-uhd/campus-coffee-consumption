package de.seuhd.campuscoffee.data.implementations
import de.seuhd.campuscoffee.data.persistence.events.EventSourcedWriter
import de.seuhd.campuscoffee.domain.model.CoffeePrice
import de.seuhd.campuscoffee.domain.ports.data.CoffeePriceDataService
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Event sourcing coffee-price data adapter, the only persistence path. A Decorator around the relational
 * [CoffeePriceDataServiceImpl] (both adapters for the same `CoffeePriceDataService` port, so it is
 * `@Primary`). The `delegate` is typed against the port and pinned to the relational bean with
 * `@Qualifier(CoffeePriceDataServiceImpl.BEAN_NAME)`, so the wrapper shares only the interface with the
 * wrappee. It delegates reads and `findCurrent` and writes each price change event-first. The first
 * price is created through the same `upsert` (no id) and thereafter updated in place, so the log keeps the
 * full price history.
 */
@Service
@Primary
class EventSourcedCoffeePriceDataService(
    @param:Qualifier(CoffeePriceDataServiceImpl.BEAN_NAME) private val delegate: CoffeePriceDataService,
    private val writer: EventSourcedWriter
) : CoffeePriceDataService by delegate {
    @Transactional
    override fun upsert(domain: CoffeePrice): CoffeePrice =
        writer.upsert(
            domain,
            delegate::getById,
            { id, now -> domain.copy(id = id, createdAt = now, updatedAt = now) },
            { existing, now -> domain.copy(createdAt = existing.createdAt, updatedAt = now) }
        )

    @Transactional
    override fun delete(id: UUID) = writer.delete(CoffeePrice::class, id, delegate::getById)

    @Transactional
    override fun clear() = writer.clear(CoffeePrice::class, delegate::clear)
}
