package de.seuhd.campuscoffee.data.persistence.eventsourcing

import de.seuhd.campuscoffee.domain.model.ConsumptionChange
import de.seuhd.campuscoffee.domain.ports.data.ConsumptionHistoryDataService
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Reads a consumption's change history straight from the append-only event log (there is no dedicated
 * history table). It fetches the consumption's events newest first and computes each change's delta as the
 * difference between its count and the count of the immediately preceding event. To compute the delta of
 * the oldest event on a page it fetches one extra event below the page; the very first event (the insert)
 * has no predecessor, so its delta is its own count.
 */
@Service
class ConsumptionHistoryDataServiceImpl(
    private val eventRepository: EventRepository
) : ConsumptionHistoryDataService {
    override fun changes(
        consumptionId: UUID,
        limit: Int,
        offset: Int
    ): List<ConsumptionChange> {
        val pageSize = limit.coerceIn(0, MAX_LIMIT)
        if (pageSize == 0) {
            return emptyList()
        }
        val safeOffset = offset.coerceAtLeast(0)
        // fetch one extra event below the page so the oldest event on the page has a predecessor for its delta
        val events = eventRepository.findHistory(ENTITY_TYPE, consumptionId.toString(), pageSize + 1, safeOffset)
        return events.take(pageSize).mapIndexed { index, event ->
            val count = countOf(event)
            val predecessor = events.getOrNull(index + 1)
            // the delta is the diff to the previous event; the oldest event (no predecessor) is the insert,
            // so its delta is its own count
            val delta = if (predecessor != null) count - countOf(predecessor) else count
            ConsumptionChange(
                count = count,
                delta = delta,
                createdAt = requireNotNull(event.createdAt) { "An event must carry a createdAt." },
                createdBy = event.createdBy ?: SYSTEM_ACTOR,
                note = event.note
            )
        }
    }

    /** Reads the integer `count` from a consumption event's body (0 if absent, e.g. a delete event). */
    private fun countOf(event: EventEntity): Int = (event.body?.get("count") as? Number)?.toInt() ?: 0

    private companion object {
        // The stored discriminator is the LoggedEntityType label, not the class simple name; key on the
        // label so a future rename of the domain class cannot silently empty the change log (they happen to
        // be equal today, which is exactly the coincidence to not depend on).
        private val ENTITY_TYPE = LoggedEntityType.COFFEE_CONSUMPTION.label
        private const val MAX_LIMIT = 100
        private const val SYSTEM_ACTOR = "SYSTEM"
    }
}
