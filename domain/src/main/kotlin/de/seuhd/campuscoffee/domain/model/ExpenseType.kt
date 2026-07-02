package de.seuhd.campuscoffee.domain.model

/**
 * The kind of a recorded [Expense]. A cross-layer enum like [Role] and [SummaryPanel].
 *
 * - [BEANS] is a coffee-bean purchase: it links a [CoffeeBean] and carries a bean weight.
 * - [OTHER] is any other group outlay (filters, milk, repairs): no bean and no weight.
 */
enum class ExpenseType {
    /** A coffee-bean purchase: links a bean and carries a weight. */
    BEANS,

    /** Any other group outlay: no bean, no weight. */
    OTHER
}
