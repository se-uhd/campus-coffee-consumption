package de.seuhd.campuscoffee.domain.ports.api

import de.seuhd.campuscoffee.domain.exceptions.ForbiddenException
import de.seuhd.campuscoffee.domain.exceptions.ValidationException
import de.seuhd.campuscoffee.domain.model.CoffeePrice
import de.seuhd.campuscoffee.domain.model.PriceChange
import de.seuhd.campuscoffee.domain.model.User

/**
 * Service interface for the global coffee price. A port implemented by the domain and consumed by the API.
 *
 * Reading the current price is open to any member (it is shown on the landing page); changing it and
 * reading its history are admin-only. Every change is recorded as a full-state event, so the full price
 * history is retrievable from the log.
 */
interface CoffeePriceService {
    /**
     * Returns the current price per cup. A price is always seeded at bootstrap, so one always exists.
     *
     * @return the current price
     */
    fun getCurrent(): CoffeePrice

    /**
     * Sets the price per cup to [amountCents] (euro cents). Creates the single price row the first time and
     * updates it thereafter.
     *
     * @param amountCents the new price in euro cents (must be non-negative)
     * @param actingUser  the authenticated user attempting the change
     * @return the updated price
     * @throws ForbiddenException if [actingUser] is not an admin
     * @throws ValidationException if [amountCents] is negative
     */
    fun setPrice(
        amountCents: Int,
        actingUser: User
    ): CoffeePrice

    /**
     * Creates the initial price at [amountCents] if none exists yet, and returns the current price. Used by
     * the startup loader (the system actor); idempotent, so a restart or a log rebuild leaves the price
     * untouched.
     *
     * @param amountCents the initial price in euro cents
     * @return the current price (the newly created one, or the existing one)
     */
    fun ensureInitialPrice(amountCents: Int): CoffeePrice

    /**
     * Returns the full price history, newest first, read from the event log.
     *
     * @param actingUser the authenticated user attempting the read
     * @return the price changes, newest first
     * @throws ForbiddenException if [actingUser] is not an admin
     */
    fun priceHistory(actingUser: User): List<PriceChange>

    /** Clears the price (dev/test reset only; destructive). */
    fun clear()
}
