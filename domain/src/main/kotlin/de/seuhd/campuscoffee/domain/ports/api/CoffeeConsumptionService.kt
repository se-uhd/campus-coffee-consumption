package de.seuhd.campuscoffee.domain.ports.api

import de.seuhd.campuscoffee.domain.exceptions.ConcurrentUpdateException
import de.seuhd.campuscoffee.domain.exceptions.ConflictException
import de.seuhd.campuscoffee.domain.exceptions.ForbiddenException
import de.seuhd.campuscoffee.domain.exceptions.NotFoundException
import de.seuhd.campuscoffee.domain.exceptions.ValidationException
import de.seuhd.campuscoffee.domain.model.CancellableIncrement
import de.seuhd.campuscoffee.domain.model.CoffeeConsumption
import de.seuhd.campuscoffee.domain.model.ConsumptionChange
import de.seuhd.campuscoffee.domain.model.User
import java.util.UUID

/**
 * Service interface for coffee-consumption operations: the per-user running count and its change log.
 *
 * This is a port implemented by the domain layer and consumed by the API layer. Each mutation
 * ([applyDelta], [setTotal]) loads the target user's consumption, applies the new count, and upserts it,
 * which the event-sourced data adapter records as a full-state event. There is deliberately no new
 * activity machinery: the count advances through the same upsert path a review's approval count did.
 *
 * Authorization is by [User.role] and ownership: a user may add a coffee or undo a recent one only on
 * their own count, an admin may act on anyone, and the absolute override ([setTotal], an admin correction)
 * is admin-only. A deactivated user is authenticated read-only, so their mutations are rejected.
 *
 * Extends [CrudService] for the generic operations the fixtures, dev endpoint, and event-sourcing
 * projector rely on; the methods here add the consumption-specific behavior.
 */
interface CoffeeConsumptionService : CrudService<CoffeeConsumption, UUID> {
    /**
     * Returns the consumption (current total) of the user with [userId], on behalf of [actingUser].
     *
     * @param userId     the id of the user whose consumption to read
     * @param actingUser the authenticated user attempting the read
     * @throws NotFoundException if no user (or consumption) exists for [userId]
     * @throws ForbiddenException if [actingUser] is neither that user nor an admin
     */
    fun getByUserId(
        userId: UUID,
        actingUser: User
    ): CoffeeConsumption

    /**
     * Applies a single-step change ([delta] of `+1` or `-1`) to the count of the user with [userId] and
     * returns the updated consumption.
     *
     * @param userId     the id of the user whose count to change
     * @param delta      the change to apply, either `+1` or `-1`
     * @param actingUser the authenticated user attempting the change
     * @throws NotFoundException if no consumption exists for [userId]
     * @throws ForbiddenException if [actingUser] may not change this count (not the owner/an admin, or a
     *   deactivated owner)
     * @throws ValidationException if [delta] is not `+1`/`-1` (a malformed request, 400)
     * @throws ConflictException if a `-1` would drive the count below zero (a conflict with the current
     *   value, 409)
     * @throws ConcurrentUpdateException if a concurrent change won the optimistic-locking race
     */
    fun applyDelta(
        userId: UUID,
        delta: Int,
        actingUser: User
    ): CoffeeConsumption

    /**
     * Sets the count of the user with [userId] to an explicit value (an admin correction; any non-negative
     * value, including zero). An optional [note] documents the change in the event log.
     *
     * This absolute override is authoritative: it writes the new [total] as the count's full state, so it
     * intentionally supersedes any concurrent self-scan. A user `+1` that lands between the admin's read
     * and write is overwritten by design (the admin asserts an absolute value, not a relative adjustment),
     * so this write is deliberately not guarded against that race.
     *
     * @param userId     the id of the user whose count to set
     * @param total      the new, non-negative count
     * @param note       an optional admin annotation recorded with the change (e.g. the payment reason)
     * @param actingUser the authenticated user attempting the override
     * @throws NotFoundException if no consumption exists for [userId]
     * @throws ForbiddenException if [actingUser] is not an admin
     * @throws ValidationException if [total] is negative
     */
    fun setTotal(
        userId: UUID,
        total: Int,
        note: String?,
        actingUser: User
    ): CoffeeConsumption

    /**
     * Returns a page of the changes for the user with [userId], newest first, read from the event log.
     * Subject to the same self-or-admin read rule as [getByUserId].
     *
     * @param userId     the id of the user whose history to read
     * @param limit      the maximum number of changes to return
     * @param offset     the number of changes to skip from the newest (for paging)
     * @param actingUser the authenticated user attempting the read
     * @throws NotFoundException if no consumption exists for [userId]
     * @throws ForbiddenException if [actingUser] is neither that user nor an admin
     */
    fun recentChanges(
        userId: UUID,
        limit: Int,
        offset: Int,
        actingUser: User
    ): List<ConsumptionChange>

    /**
     * Undoes the calling user's most recent coffee within the grace period (only the owner may do this;
     * an admin uses [setTotal] instead). It reverts the most recent un-cancelled own increment, crediting
     * exactly the price it was charged at, so undoing nets to zero.
     *
     * @param userId     the id of the user undoing a coffee (must be [actingUser])
     * @param actingUser the authenticated user
     * @return the updated consumption
     * @throws ForbiddenException if [actingUser] is not the owner, or is deactivated
     * @throws ConflictException if there is no recent coffee to undo or the grace period has passed
     * @throws ConcurrentUpdateException if a concurrent change won the optimistic-locking race
     */
    fun cancel(
        userId: UUID,
        actingUser: User
    ): CoffeeConsumption

    /**
     * Returns the user's most recent coffee that is still within the grace period to undo, or null if
     * there is none, used to show or hide the undo affordance. Subject to the same self-or-admin read rule
     * as [getByUserId].
     *
     * @param userId     the id of the user whose cancellable coffee to check
     * @param actingUser the authenticated user attempting the read
     * @throws ForbiddenException if [actingUser] is neither that user nor an admin
     */
    fun cancellableIncrement(
        userId: UUID,
        actingUser: User
    ): CancellableIncrement?

    /**
     * Creates the consumption for a freshly created [user] at `count = 0`. Invoked internally when a user
     * is created (and by the fixtures); not an authenticated, client-facing operation.
     *
     * @param user the persisted user to create a consumption for
     * @return the created consumption
     */
    fun createForUser(user: User): CoffeeConsumption
}
