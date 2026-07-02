package de.seuhd.campuscoffee.domain.model

import java.time.LocalDateTime
import java.util.UUID

/**
 * Immutable expense domain model: a recorded group outlay. It references the [buyer] (flattened to a user id
 * in the event body, the way a consumption references its user) and carries the total [amountCents] paid, in
 * euro cents.
 *
 * An expense has an [expenseType]: a [ExpenseType.BEANS] purchase links a [bean] and carries a
 * [weightGrams], while an [ExpenseType.OTHER] outlay has neither (both null). The domain service enforces
 * these type rules before the upsert.
 *
 * The total is split into a [privateAmountCents] portion (paid from the buyer's own pocket, which credits
 * the buyer's balance) and a [kittyAmountCents] portion (paid from the communal kitty, which draws the
 * kitty down). The two always sum to [amountCents], enforced by the domain service before the upsert and
 * backed by a database CHECK. A user recording their own purchase always books it as 100% private to
 * themselves; only an admin may set a split or attribute the private portion to another user.
 */
data class Expense(
    override val id: UUID? = null,
    val createdAt: LocalDateTime? = null,
    val updatedAt: LocalDateTime? = null,
    val buyer: User,
    val expenseType: ExpenseType = ExpenseType.BEANS,
    val bean: CoffeeBean? = null,
    val weightGrams: Int? = null,
    val amountCents: Int,
    val privateAmountCents: Int,
    val kittyAmountCents: Int,
    val note: String? = null
) : DomainModel<UUID>
