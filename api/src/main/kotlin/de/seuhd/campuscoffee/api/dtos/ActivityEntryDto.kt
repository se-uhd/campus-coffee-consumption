package de.seuhd.campuscoffee.api.dtos

import com.fasterxml.jackson.annotation.JsonInclude
import de.seuhd.campuscoffee.domain.model.ActivityEntryType
import java.time.LocalDateTime
import java.util.UUID

/**
 * Response DTO for one row of an activity feed (a user's or the kitty's). [id] is a stable per-entry key
 * (the underlying event's id) for client-side paging and deduplication. [type] discriminates the row;
 * [amountCents] is its signed effect on the balance and [runningBalanceCents] the balance after it (euro
 * cents). [count]/[delta] are present only for a consumption row and [weightGrams] only for an expense row,
 * so they are omitted when absent. [privateAmountCents]/[kittyAmountCents] are present together only on the
 * **admin** views of a split bean purchase (its user-funded and kitty-funded portions): both the user's
 * PRIVATE_EXPENSE row and the kitty history's KITTY_EXPENSE row carry them. The user-serving read
 * strips both, so a user never sees the split (their purchases read as 100% private).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ActivityEntryDto(
    val type: ActivityEntryType,
    val id: UUID,
    val createdAt: LocalDateTime,
    val createdBy: String,
    val note: String? = null,
    val amountCents: Long,
    val runningBalanceCents: Long,
    val count: Int? = null,
    val delta: Int? = null,
    val weightGrams: Int? = null,
    val privateAmountCents: Long? = null,
    val kittyAmountCents: Long? = null
)
