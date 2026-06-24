package de.seuhd.campuscoffee.domain.ports.api

import de.seuhd.campuscoffee.domain.exceptions.ForbiddenException
import de.seuhd.campuscoffee.domain.exceptions.NotFoundException
import de.seuhd.campuscoffee.domain.exceptions.ValidationException
import de.seuhd.campuscoffee.domain.model.Payment
import de.seuhd.campuscoffee.domain.model.User
import java.util.UUID

/**
 * Service interface for kitty money movements. A port implemented by the domain and consumed by the API.
 * Every operation is admin-only: only an admin manages the communal kitty. Payments are never edited; a
 * mistake is corrected with a compensating entry.
 */
interface PaymentService {
    /**
     * Records that a member paid money into the kitty (a deposit): credits the member's balance and
     * feeds the kitty.
     *
     * @param userId      the member who paid
     * @param amountCents the amount paid in euro cents (must be positive)
     * @param note        an optional free-text note
     * @param actingUser  the authenticated user attempting the record
     * @return the recorded deposit
     * @throws ForbiddenException if [actingUser] is not an admin
     * @throws NotFoundException if no user exists for [userId]
     * @throws ValidationException if [amountCents] is not positive
     */
    fun recordDeposit(
        userId: UUID,
        amountCents: Int,
        note: String?,
        actingUser: User
    ): Payment

    /**
     * Adjusts the kitty without involving a member (an initial float, or a correction). The amount may be
     * negative to remove money from the kitty.
     *
     * @param amountCents the signed adjustment in euro cents (must not be zero)
     * @param note        an optional free-text note
     * @param actingUser  the authenticated user attempting the adjustment
     * @return the recorded adjustment
     * @throws ForbiddenException if [actingUser] is not an admin
     * @throws ValidationException if [amountCents] is zero
     */
    fun adjustKitty(
        amountCents: Int,
        note: String?,
        actingUser: User
    ): Payment

    /** Clears all payments (dev/test reset only; destructive). */
    fun clear()
}
