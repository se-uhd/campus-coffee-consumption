package de.seuhd.campuscoffee.data.implementations
import de.seuhd.campuscoffee.data.implementations.CoffeeConsumptionDataServiceImpl
import de.seuhd.campuscoffee.data.persistence.events.EventSourcedWriter
import de.seuhd.campuscoffee.domain.model.CoffeeConsumption
import de.seuhd.campuscoffee.domain.ports.data.CoffeeConsumptionDataService
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Event sourcing coffee-consumption data adapter, the only persistence path. A copy of
 * [EventSourcedUserDataService]: a Decorator around the relational [CoffeeConsumptionDataServiceImpl] (both
 * adapters for the same `CoffeeConsumptionDataService` port, so it is `@Primary`), delegating reads and
 * `getByUserId` and writing each `+1`/`-1`/override event-first. Every mutation is recorded as a
 * full-state event and projected into the read table in one transaction.
 */
@Service
@Primary
class EventSourcedCoffeeConsumptionDataService(
    private val delegate: CoffeeConsumptionDataServiceImpl,
    private val writer: EventSourcedWriter
) : CoffeeConsumptionDataService by delegate {
    @Transactional
    override fun upsert(domain: CoffeeConsumption): CoffeeConsumption =
        writer.upsert(
            domain,
            delegate::getById,
            { id, now -> domain.copy(id = id, createdAt = now, updatedAt = now) },
            { existing, now -> domain.copy(createdAt = existing.createdAt, updatedAt = now) }
        )

    @Transactional
    override fun delete(id: UUID) = writer.delete(CoffeeConsumption::class, id, delegate::getById)

    @Transactional
    override fun clear() = writer.clear(CoffeeConsumption::class, delegate::clear)
}
