package de.seuhd.campuscoffee.data.implementations

import de.seuhd.campuscoffee.domain.ports.data.KittyLock
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.springframework.stereotype.Service

/**
 * PostgreSQL [KittyLock] backed by a transaction-level advisory lock (`pg_advisory_xact_lock`) on a fixed
 * application key. The lock is held until the surrounding transaction commits or rolls back, so concurrent
 * kitty-mutating transactions serialize and each reads the previous one's committed balance. It uses no
 * table row — a pure Postgres advisory lock keyed on [KITTY_LOCK_KEY].
 */
@Service
class PostgresKittyLock(
    @PersistenceContext private val entityManager: EntityManager
) : KittyLock {
    override fun lockForUpdate() {
        entityManager
            .createNativeQuery("SELECT pg_advisory_xact_lock(:key)")
            .setParameter("key", KITTY_LOCK_KEY)
            .resultList
    }

    private companion object {
        // a fixed, application-wide key identifying the single kitty aggregate lock (no other advisory
        // locks are used in this application, so any constant works as long as it is the same everywhere)
        private const val KITTY_LOCK_KEY = 0xC0FFEEL
    }
}
