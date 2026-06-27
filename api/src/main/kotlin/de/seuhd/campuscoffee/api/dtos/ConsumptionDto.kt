package de.seuhd.campuscoffee.api.dtos

/**
 * Response DTO for the admin view of a user's coffee consumption: the current [total] plus a page of the
 * most recent [changes] (newest first). Returned by the admin consumption endpoints under
 * `/api/users/{id}/consumption`; users read their balance and history through the unified activity
 * ([UserSummaryDto] and [ActivityEntryDto]) instead. [total] is the derived current count carried along for
 * convenience. Plain (no [Dto] id base) because it carries no entity id.
 */
data class ConsumptionDto(
    val total: Int,
    val changes: List<ConsumptionChangeDto>
)
