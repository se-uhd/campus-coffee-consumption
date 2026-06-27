package de.seuhd.campuscoffee.data.persistence.entities

import jakarta.persistence.Column
import jakarta.persistence.Id
import jakarta.persistence.Table

/**
 * Read-model projection of the single global kitty balance in euro cents. Derived from the payment and
 * expense streams and maintained on every kitty-moving write, so the user landing summary and the
 * kitty-overdraw guard read one number instead of replaying the whole global money stream. A single row
 * pinned to [SINGLETON_ID] by the table's `CHECK (id = 1)` constraint; [balanceCents] is the current kitty
 * balance.
 *
 * Single-row convention: this is a plain derived cache with no natural id, so the fixed integer id is itself
 * the row guard (`CHECK (id = 1)`), the lightest form. The other single-row table, `coffee_prices`, instead
 * carries a separate `is_singleton` guard column, because it is an event-sourced entity whose `id` is a
 * meaningful per-write UUID that cannot double as the guard. So: an event-sourced single-row entity uses
 * `is_singleton`; a plain single-row table uses `id integer CHECK (id = 1)`.
 */
@jakarta.persistence.Entity
@Table(name = "kitty_balance")
class KittyBalanceEntity {
    @field:Id
    @field:Column(name = "id")
    var id: Int = SINGLETON_ID

    @field:Column(name = "balance_cents")
    var balanceCents: Long = 0

    companion object {
        /** The fixed id of the single kitty-balance row (matched by the table's `CHECK (id = 1)` constraint). */
        const val SINGLETON_ID: Int = 1
    }
}
