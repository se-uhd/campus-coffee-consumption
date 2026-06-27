package de.seuhd.campuscoffee.data.persistence.events
import de.seuhd.campuscoffee.data.persistence.projection.BalanceProjectionMaintainer
import de.seuhd.campuscoffee.data.persistence.repositories.CoffeeConsumptionRepository
import de.seuhd.campuscoffee.data.persistence.repositories.CoffeePriceRepository
import de.seuhd.campuscoffee.data.persistence.repositories.EventRepository
import de.seuhd.campuscoffee.data.persistence.repositories.ExpenseRepository
import de.seuhd.campuscoffee.data.persistence.repositories.PaymentRepository
import de.seuhd.campuscoffee.data.persistence.repositories.UserRepository
import de.seuhd.campuscoffee.domain.ports.system.StartupTaskService
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
    private val balanceProjection: BalanceProjectionMaintainer,
    private val userRepository: UserRepository,
    private val coffeeConsumptionRepository: CoffeeConsumptionRepository,
    private val coffeePriceRepository: CoffeePriceRepository,
    private val expenseRepository: ExpenseRepository,
    private val paymentRepository: PaymentRepository
) : StartupTaskService {
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
        // replay the log in bounded, seq-ordered batches (keyset paging) so the rebuild never holds the whole
        // log in memory at once
        var afterSeq = 0L
        var total = 0
        while (true) {
            val batch = eventRepository.findBatchAfterSeq(afterSeq, BATCH_SIZE)
            if (batch.isEmpty()) {
                break
            }
            batch.forEach { projector.apply(it) }
            afterSeq = requireNotNull(batch.last().seq) { "A replayed event must carry a seq." }
            total += batch.size
        }
        // the projector rebuilds only the relational rows; recompute the balance projections from the log
        // once the read tables (and user logins) are in place
        balanceProjection.rebuildAll()
        log.info { "Rebuilt the read model and balances from $total events in the log." }
    }

    /**
     * Empties the read tables in foreign key order: the balance projections first, then the children that
     * reference users (consumptions, expenses, payments) before users; the price is independent and is
     * cleared too. Clearing the balance projections here, rather than relying on the `user_balance` cascade
     * from the user delete, keeps the kitty_balance row from going stale during the replay.
     */
    private fun clearReadTables() {
        balanceProjection.clear()
        coffeeConsumptionRepository.deleteAllInBatch()
        expenseRepository.deleteAllInBatch()
        paymentRepository.deleteAllInBatch()
        userRepository.deleteAllInBatch()
        coffeePriceRepository.deleteAllInBatch()
    }

    companion object {
        /** The rebuild runs before the fixture loader, so the loader's empty-users check sees the rebuilt data. */
        const val ORDER = 100

        /** How many events to load per replay batch, bounding the rebuild's memory footprint. */
        private const val BATCH_SIZE = 500

        private val log = KotlinLogging.logger {}
    }
}
