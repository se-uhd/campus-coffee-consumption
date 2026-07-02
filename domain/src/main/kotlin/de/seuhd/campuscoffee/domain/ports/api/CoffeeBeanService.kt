package de.seuhd.campuscoffee.domain.ports.api

import de.seuhd.campuscoffee.domain.exceptions.DuplicationException
import de.seuhd.campuscoffee.domain.exceptions.ForbiddenException
import de.seuhd.campuscoffee.domain.exceptions.NotFoundException
import de.seuhd.campuscoffee.domain.exceptions.ValidationException
import de.seuhd.campuscoffee.domain.model.CoffeeBean
import de.seuhd.campuscoffee.domain.model.CoffeeBeanRatings
import de.seuhd.campuscoffee.domain.model.User
import java.util.UUID

/**
 * Service interface for the coffee-bean catalog. A port implemented by the domain and consumed by the API.
 *
 * Reads (the selectable list) are available to any authenticated caller; renaming and merging are admin-only.
 * Beans are created implicitly when a bean name is first used (recording a `BEANS` purchase or the migration),
 * via [resolveOrCreate]; there is no explicit create endpoint.
 */
interface CoffeeBeanService {
    /**
     * Lists the selectable beans (live, non-merged), ordered by name, for the rating dropdown and the
     * expense bean autocomplete.
     *
     * @return the live beans; never null, but may be empty
     */
    fun listSelectable(): List<CoffeeBean>

    /**
     * Resolves a bean by name, creating a new live bean when none matches. The name is normalized (trimmed,
     * inner whitespace collapsed) and matched case-insensitively against the canonical beans. Used by the
     * expense-recording path and the migration; not itself an authorization boundary (the caller authorizes).
     *
     * @param rawName the bean name entered by the user (normalized here)
     * @return the existing or newly created canonical bean
     * @throws ValidationException if [rawName] is blank after normalization
     */
    fun resolveOrCreate(rawName: String): CoffeeBean

    /**
     * Renames a bean (admin-only). The new name is normalized and must stay unique among canonical beans.
     *
     * @param beanId     the id of the bean to rename
     * @param newName    the new name (normalized here)
     * @param actingUser the authenticated user attempting the rename
     * @return the renamed bean
     * @throws ForbiddenException if [actingUser] is not an admin
     * @throws NotFoundException if no bean exists for [beanId]
     * @throws ValidationException if [newName] is blank after normalization
     * @throws DuplicationException if another canonical bean already has that name
     */
    fun rename(
        beanId: UUID,
        newName: String,
        actingUser: User
    ): CoffeeBean

    /**
     * Merges one bean into another (admin-only): the source becomes a tombstone pointing at [targetBeanId],
     * so its ratings and expenses resolve through to the target. The target must be a canonical bean.
     *
     * @param beanId       the id of the bean to merge away (the source)
     * @param targetBeanId the id of the canonical bean to merge into (the target)
     * @param actingUser   the authenticated user attempting the merge
     * @return the merged (tombstoned) source bean
     * @throws ForbiddenException if [actingUser] is not an admin
     * @throws NotFoundException if either bean does not exist
     * @throws ValidationException if the source equals the target, or the target is not canonical
     */
    fun merge(
        beanId: UUID,
        targetBeanId: UUID,
        actingUser: User
    ): CoffeeBean

    /**
     * Returns the bean with the given id.
     *
     * @param beanId the id of the bean
     * @return the bean
     * @throws NotFoundException if no bean exists for [beanId]
     */
    fun getById(beanId: UUID): CoffeeBean

    /**
     * Returns the most recently purchased bean (the bean of the newest `BEANS` expense), or null if no bean
     * has been purchased yet. Used to preselect a default in the rating prompt.
     *
     * @return the most recently purchased bean, or null
     */
    fun mostRecentlyPurchased(): CoffeeBean?

    /**
     * Returns the rating rows for every canonical bean (average rating, vote count, latest rating, latest
     * purchase), with votes and purchases of merged beans counted under their canonical target. Highest
     * average first (a bean with no votes sorts last), then most votes, then name.
     *
     * @return the rating rows
     */
    fun ratings(): List<CoffeeBeanRatings>

    /** Clears all beans (dev/test reset only; destructive). */
    fun clear()
}
