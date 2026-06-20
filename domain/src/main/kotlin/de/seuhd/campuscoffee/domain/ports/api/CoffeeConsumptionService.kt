package de.seuhd.campuscoffee.domain.ports.api

import de.seuhd.campuscoffee.domain.exceptions.ConcurrentUpdateException
import de.seuhd.campuscoffee.domain.exceptions.ConflictException
import de.seuhd.campuscoffee.domain.exceptions.ForbiddenException
import de.seuhd.campuscoffee.domain.exceptions.NotFoundException
import de.seuhd.campuscoffee.domain.exceptions.ValidationException
import de.seuhd.campuscoffee.domain.model.CoffeeConsumption
import de.seuhd.campuscoffee.domain.model.ConsumptionChange
import de.seuhd.campuscoffee.domain.model.User
import java.util.UUID

/**
 * Service interface for coffee-consumption operations: the per-member running count and its change log.
 *
 * This is a port implemented by the domain layer and consumed by the API layer. Each mutation
 * ([applyDelta], [setTotal]) loads the target member's consumption, applies the new count, and upserts it
 * — which the event-sourced data adapter records as a full-state event. There is deliberately no new
 * ledger machinery: the count advances through the same upsert path a review's approval count did.
 *
 * Authorization is by [User.role] and ownership: a member may view and `+1`/`-1` only their own count, an
 * admin may act on anyone, and the absolute override ([setTotal], which also covers reset-after-payment)
 * is admin-only. A deactivated member is authenticated read-only, so their mutations are rejected.
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
     * Sets the count of the user with [userId] to an explicit value (admin override / edit mode; a value
     * of zero is the reset after payment). An optional [note] documents the change in the event log.
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
     * Creates the consumption for a freshly created [user] at `count = 0`. Invoked internally when a user
     * is created (and by the fixtures); not an authenticated, client-facing operation.
     *
     * @param user the persisted user to create a consumption for
     * @return the created consumption
     */
    fun createForUser(user: User): CoffeeConsumption
}
