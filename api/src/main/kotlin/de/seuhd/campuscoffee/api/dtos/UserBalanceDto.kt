package de.seuhd.campuscoffee.api.dtos

import java.util.UUID

/**
 * Response DTO for one row of the admin balance overview: a member and their current coffee [count] and
 * [balanceCents] (negative ⇒ they owe the fund). Money is in euro cents.
 */
data class UserBalanceDto(
    val userId: UUID,
    val loginName: String,
    val firstName: String,
    val lastName: String,
    val count: Int,
    val balanceCents: Long
)
