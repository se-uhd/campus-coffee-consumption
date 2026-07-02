package de.seuhd.campuscoffee.data.persistence.entities

import de.seuhd.campuscoffee.domain.model.CoffeeBean
import de.seuhd.campuscoffee.domain.model.CoffeeConsumption
import de.seuhd.campuscoffee.domain.model.CoffeePrice
import de.seuhd.campuscoffee.domain.model.CoffeeRating
import de.seuhd.campuscoffee.domain.model.DomainModel
import de.seuhd.campuscoffee.domain.model.Expense
import de.seuhd.campuscoffee.domain.model.Payment
import de.seuhd.campuscoffee.domain.model.User
import kotlin.reflect.KClass

/**
 * The closed set of domain types recorded in the append-only event log, used as the `events.entity_type`
 * discriminator. Each constant pairs the persisted [label] (the string stored in the column) with its
 * domain [domainClass].
 *
 * Having an enum rather than a bare string buys two things: the [ReadModelProjector]'s dispatch is a `when`
 * over these constants, so the compiler forces a projection branch for every type (a new logged entity
 * cannot be forgotten), and the label/class mapping lives in one place. The [label] is written out
 * explicitly and decoupled from the constant name, so renaming a Kotlin class or enum constant never
 * changes what is already stored in the log.
 *
 * This is a data-layer concept: `entity_type` exists only because of event sourcing, which the domain is
 * deliberately unaware of (mirroring the sibling [ChangeType] enum). The `events.entity_type` column stays
 * an unconstrained varchar so the log remains extensible; the valid set is enforced here, in the
 * application, and [ofLabel] fails loudly on a label this version does not know.
 *
 * @property label the string stored in the `events.entity_type` column
 * @property domainClass the domain model class this type maps to
 */
enum class LoggedEntityType(
    val label: String,
    val domainClass: KClass<out DomainModel<*>>
) {
    USER("User", User::class),
    COFFEE_CONSUMPTION("CoffeeConsumption", CoffeeConsumption::class),
    COFFEE_PRICE("CoffeePrice", CoffeePrice::class),
    EXPENSE("Expense", Expense::class),
    PAYMENT("Payment", Payment::class),
    COFFEE_BEAN("CoffeeBean", CoffeeBean::class),
    COFFEE_RATING("CoffeeRating", CoffeeRating::class)
    ;

    companion object {
        private val BY_LABEL = entries.associateBy { it.label }
        private val BY_CLASS = entries.associateBy { it.domainClass }

        /**
         * Returns the type for a persisted label, failing if no constant declares it (an event written by a
         * version that knew a type this one does not).
         *
         * @param label the persisted `entity_type` label
         */
        fun ofLabel(label: String): LoggedEntityType =
            BY_LABEL[label] ?: error("Unknown logged entity type label '$label'.")

        /**
         * Returns the type for a domain class, failing if that class is not a logged entity (so a write of
         * an unregistered type is caught instead of silently stored under its simple name).
         *
         * @param domainType the domain type whose logged-entity type is looked up
         */
        fun of(domainType: KClass<out DomainModel<*>>): LoggedEntityType =
            BY_CLASS[domainType] ?: error("'${domainType.simpleName}' is not a logged entity type.")
    }
}
