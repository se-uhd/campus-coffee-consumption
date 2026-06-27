package de.seuhd.campuscoffee.domain.model

/**
 * A user together with their current coffee [count] and [balanceCents] (negative ⇒ they owe the fund),
 * for the admin overview of everyone's standing. A read-only projection. Money is in euro cents.
 */
data class UserBalance(
    val user: User,
    val count: Int,
    val balanceCents: Long
)
