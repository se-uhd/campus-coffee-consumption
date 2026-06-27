package de.seuhd.campuscoffee.domain.ports.data

import de.seuhd.campuscoffee.domain.model.ConsumptionChange
import java.util.UUID

/**
 * Port for reading a user's consumption history straight from the append-only event log (rather than a
 * dedicated table). Implemented by the event sourcing adapter, which queries the `events` rows for the
 * consumption, computes each change's delta against the previous event, and carries the `created_by` /
 * `created_at` / `note` metadata. Kept separate from [CoffeeConsumptionDataService] because it is
 * inherently event-log-backed and has no relational read model equivalent.
 */
interface ConsumptionHistoryDataService {
    /**
     * Returns a page of the changes for the consumption with [consumptionId], newest first.
     *
     * @param consumptionId the id of the consumption whose history to read
     * @param limit         the maximum number of changes to return
     * @param offset        the number of changes to skip from the newest (for paging)
     * @return the changes, newest first (at most [limit])
     */
    fun changes(
        consumptionId: UUID,
        limit: Int,
        offset: Int
    ): List<ConsumptionChange>
}
