package de.seuhd.campuscoffee.domain.ports.data

/**
 * A transaction-scoped lock that serializes the kitty-affecting writes (a kitty adjustment and the kitty
 * portion of an admin expense). The kitty's non-negative invariant is an aggregate over many event rows with
 * no single row to lock, so a per-row `@Version` cannot guard it: two concurrent writes would each read the
 * balance, both pass the negative-balance check, and both commit, overdrawing the kitty. Acquiring this lock
 * at the start of a kitty-mutating transaction serializes the read-modify-append so the check stays sound.
 */
interface KittyLock {
    /**
     * Acquires the kitty lock for the current transaction, blocking until any other holder's transaction
     * ends. The lock is released automatically when this transaction commits or rolls back, so the next
     * waiter then reads the committed balance.
     */
    fun lockForUpdate()
}
