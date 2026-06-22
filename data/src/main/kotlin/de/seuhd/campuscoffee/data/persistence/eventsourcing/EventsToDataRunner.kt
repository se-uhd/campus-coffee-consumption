package de.seuhd.campuscoffee.data.persistence.eventsourcing

import de.seuhd.campuscoffee.data.persistence.repositories.CoffeeConsumptionRepository
import de.seuhd.campuscoffee.data.persistence.repositories.CoffeePriceRepository
import de.seuhd.campuscoffee.data.persistence.repositories.ExpenseRepository
import de.seuhd.campuscoffee.data.persistence.repositories.PaymentRepository
import de.seuhd.campuscoffee.data.persistence.repositories.UserRepository
import de.seuhd.campuscoffee.domain.ports.StartupTask
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * Rebuilds the relational read tables from the event log on startup, when
 * `campus-coffee.persistence.events-to-data-on-startup` is true: clears the tables and replays every event
 * in append order through the [ReadModelProjector]. An event sourcing demonstration kept as an opt-in
 * runner (the only persistence path is event sourcing, so there is no mode check). It skips when the log is
 * empty, so it cannot clear a populated read model with nothing to replay back into it.
 *
 * The replay writes the ids and the `createdAt`/`updatedAt` from the event bodies. The consumption's
 * optimistic locking version column restarts from zero, which has no effect because nothing compares a
 * version across a rebuild.
 */
@Component
@ConditionalOnProperty(name = ["campus-coffee.persistence.events-to-data-on-startup"], havingValue = "true")
class EventsToDataRunner(
    private val eventRepository: EventRepository,
    private val projector: ReadModelProjector,
    private val userRepository: UserRepository,
    private val coffeeConsumptionRepository: CoffeeConsumptionRepository,
    private val coffeePriceRepository: CoffeePriceRepository,
    private val expenseRepository: ExpenseRepository,
    private val paymentRepository: PaymentRepository
) : StartupTask {
    override val order = ORDER

    @Transactional
    override fun run() = rebuildFromLog()

    /**
     * Clears the read tables and replays every event in append order to rebuild the read model. Skips when
     * the log is empty (so it never wipes a populated read model with nothing to replay back).
     */
    @Transactional
    fun rebuildFromLog() {
        if (eventRepository.count() == 0L) {
            // an empty log against possibly-populated tables: rebuilding would only wipe them, so refuse
            log.warn { "Skipping the events-to-data rebuild: the event log is empty; not clearing the read tables." }
            return
        }
        clearReadTables()
        val events = eventRepository.findAllByOrderBySeqAsc()
        events.forEach { projector.apply(it) }
        log.info { "Rebuilt the read model from ${events.size} events in the log." }
    }

    /**
     * Empties the read tables in foreign key order: the children that reference users (consumptions,
     * expenses, payments) before users; the price is independent and is cleared too.
     */
    private fun clearReadTables() {
        coffeeConsumptionRepository.deleteAllInBatch()
        expenseRepository.deleteAllInBatch()
        paymentRepository.deleteAllInBatch()
        userRepository.deleteAllInBatch()
        coffeePriceRepository.deleteAllInBatch()
    }

    companion object {
        /** The rebuild runs before the fixture loader, so the loader's empty-users check sees the rebuilt data. */
        const val ORDER = 100
        private val log = KotlinLogging.logger {}
    }
}
