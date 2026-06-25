package de.seuhd.campuscoffee.domain.ports.data

import java.util.UUID

/**
 * Transaction-scoped advisory locks that serialize the writes maintaining the balance projections, so a
 * concurrent recompute cannot lost-update a projection row (the projection rows are deliberately
 * unversioned, so a per-row `@Version` cannot guard them) and the kitty-overdraw check cannot race.
 *
 * Two independent locks, acquired in the fixed order **kitty before member** wherever a single write takes
 * both (a deposit or a kitty-split expense moves both the kitty and a member), so the lock order is global
 * and the paths cannot deadlock:
 *
 * - [lockKitty] guards the single kitty aggregate. The kitty's non-negative invariant spans many event rows
 *   with no single row to lock, so two concurrent kitty writes could each read the balance, both pass the
 *   negative-balance check, and both commit, overdrawing the fund. A kitty-mutating service takes this lock
 *   around the read-modify-append so the overdraw check stays sound, and the projection itself takes it
 *   around every kitty recompute so even a write with no overdraw check (a deposit, a delete) cannot
 *   lost-update the kitty row.
 * - [lockMember] guards one member's balance row. A member's balance is recomputed by several unrelated
 *   writes (a self-scan, an admin step, the member's own purchase, a deposit crediting them), which share no
 *   versioned row, so two concurrent recomputes for the same member are a classic lost update. The
 *   projection takes this lock around every member recompute so the stored balance cannot drift from the
 *   authoritative log walk.
 */
interface BalanceLock {
    /**
     * Acquires the single kitty lock for the current transaction, blocking until any other holder's
     * transaction ends. Released automatically on commit or rollback, so the next waiter reads the
     * committed balance. Reentrant within a transaction (a service that already holds it for the overdraw
     * check re-acquires it harmlessly when the projection recomputes the kitty).
     */
    fun lockKitty()

    /**
     * Acquires the per-member balance lock for the current transaction, blocking until any other holder's
     * transaction ends. Released automatically on commit or rollback.
     *
     * @param userId the member whose balance row to serialize.
     */
    fun lockMember(userId: UUID)
}
