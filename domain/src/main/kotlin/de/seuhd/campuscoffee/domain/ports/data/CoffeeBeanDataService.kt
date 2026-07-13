package de.seuhd.campuscoffee.domain.ports.data

import de.seuhd.campuscoffee.domain.model.CoffeeBean
import java.util.UUID

/**
 * Port interface for coffee-bean data operations, implemented by the data layer over the projected
 * `coffee_beans` read table. Extends the generic [CrudDataService] and adds the case-insensitive
 * canonical-name lookup the bean-resolution path relies on.
 */
interface CoffeeBeanDataService : CrudDataService<CoffeeBean, UUID> {
    /**
     * Returns the live (canonical, non-merged) bean whose name matches [name] case-insensitively, or null
     * when no such bean exists.
     *
     * @param name the bean name to match (already normalized by the caller)
     * @return the matching canonical bean, or null
     */
    fun findActiveByName(name: String): CoffeeBean?

    /**
     * Returns the bean of the most recently created `BEANS` expense (by expense creation time), or null when
     * no expense references a bean.
     *
     * @return the most recently purchased bean, or null
     */
    fun findMostRecentlyPurchased(): CoffeeBean?

    /**
     * Returns the bean of the most recent rating cast by any user (by rating creation time), or null when no
     * rating exists yet. The bean may be a merge tombstone (ratings are not repointed on merge); the caller
     * resolves it to its canonical bean.
     *
     * @return the bean of the most recent rating (possibly a tombstone), or null
     */
    fun findMostRecentlyRated(): CoffeeBean?
}
