package de.seuhd.campuscoffee.api.dtos

import com.fasterxml.jackson.annotation.JsonInclude
import java.time.LocalDateTime

/**
 * Response DTO for one entry in a user's consumption change log, built from the event-log rows rather
 * than a dedicated table. [count] is the running total recorded by that change, [delta] the difference
 * from the previous change (e.g. `+1` or `-1`), [createdAt] when it was recorded, [createdBy] the login
 * name of whoever made the change (the user, an admin, or `"SYSTEM"` for the seeded data), and [note]
 * an optional admin annotation (the reason for an override), omitted when absent.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ConsumptionChangeDto(
    val count: Int,
    val delta: Int,
    val createdAt: LocalDateTime,
    val createdBy: String,
    val note: String? = null
)
