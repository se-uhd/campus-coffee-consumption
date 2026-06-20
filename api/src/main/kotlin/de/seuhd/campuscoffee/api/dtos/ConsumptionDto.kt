package de.seuhd.campuscoffee.api.dtos

/**
 * Response DTO for a member's coffee consumption: the authoritative current [total] plus a page of the
 * most recent [changes] (newest first). Every consumption endpoint — the read view *and* every mutation
 * (a `+1`/`-1` or an admin override) — returns this single shape, so one request paints the whole view
 * (count and history) and a mutation needs no follow-up read. The consumption is the change log; [total]
 * is the derived current count carried along for convenience. Plain (no [Dto] id base) because it carries
 * no entity id.
 */
data class ConsumptionDto(
    val total: Int,
    val changes: List<ConsumptionChangeDto>
)
