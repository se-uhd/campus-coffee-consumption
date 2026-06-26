package de.seuhd.campuscoffee.api.dtos

import com.fasterxml.jackson.annotation.JsonInclude
import de.seuhd.campuscoffee.domain.model.ActivityEntryType
import java.time.LocalDateTime
import java.util.UUID

/**
 * Response DTO for one row of the admin **global** activity feed: every change across all members, the kitty,
 * and the price, the all-members analogue of [ActivityEntryDto] with two running balances and two users.
 * [id] is a stable per-entry key (the underlying event's id) for client-side paging and deduplication.
 * [type] discriminates the row; [actorLogin] is who performed it; [subjectUserId]/[subjectLogin]/[subjectName]
 * are the member it concerns (all null for a kitty adjustment or a price change; [subjectName] reads
 * `(deleted member)` when only the id survives). [memberEffectCents]/[memberBalanceCents] and
 * [kittyEffectCents]/[kittyBalanceCents] are the signed effect and the running balance for the member and the
 * kitty respectively, each null when the event did not move that balance. The remaining fields are present
 * only where they apply ([count]/[delta] for a consumption, [weightGrams] and the split for an expense,
 * [priceAmountCents] for a price change), so they are omitted when absent.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class GlobalActivityEntryDto(
    val type: ActivityEntryType,
    val id: UUID,
    val createdAt: LocalDateTime,
    val actorLogin: String,
    val subjectUserId: UUID? = null,
    val subjectLogin: String? = null,
    val subjectName: String? = null,
    val note: String? = null,
    val memberEffectCents: Long? = null,
    val memberBalanceCents: Long? = null,
    val kittyEffectCents: Long? = null,
    val kittyBalanceCents: Long? = null,
    val count: Int? = null,
    val delta: Int? = null,
    val weightGrams: Int? = null,
    val privateAmountCents: Long? = null,
    val kittyAmountCents: Long? = null,
    val priceAmountCents: Int? = null
)
