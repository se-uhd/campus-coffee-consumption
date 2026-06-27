package de.seuhd.campuscoffee.data.persistence

import de.seuhd.campuscoffee.domain.ports.data.BalanceLockService
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * PostgreSQL [BalanceLockService] backed by transaction-level advisory locks (`pg_advisory_xact_lock`). Each lock
 * is held until the surrounding transaction commits or rolls back, so concurrent transactions serialize and
 * each reads the previous one's committed balance. No table row is touched, just pure Postgres advisory
 * locks.
 *
 * The kitty uses the single-key form (`pg_advisory_xact_lock(bigint)`) and a user uses the two-key form
 * (`pg_advisory_xact_lock(int, int)`). Postgres keeps the single-key and two-key forms in separate lock
 * spaces, so the kitty key and a user key can never collide even if their numeric values coincide.
 */
@Service
class BalanceLockServiceImpl(
    @PersistenceContext private val entityManager: EntityManager
) : BalanceLockService {
    override fun lockKitty() {
        entityManager
            .createNativeQuery("SELECT pg_advisory_xact_lock(:key)")
            .setParameter("key", KITTY_LOCK_KEY)
            .resultList
    }

    override fun lockUser(userId: UUID) {
        // two-key form keyed on a fixed class plus the user's id hash; a hash collision only over-serializes
        // two users (harmless extra waiting), it never crosses into the kitty's separate single-key space
        entityManager
            .createNativeQuery("SELECT pg_advisory_xact_lock(:class, :user)")
            .setParameter("class", USER_LOCK_CLASS)
            .setParameter("user", userId.hashCode())
            .resultList
    }

    private companion object {
        // a fixed, application-wide key identifying the single kitty aggregate lock (single-key space)
        private const val KITTY_LOCK_KEY = 0xC0FFEEL

        // the class half of the two-key user lock (two-key space, separate from the kitty's single-key space)
        private const val USER_LOCK_CLASS = 0xBA1A
    }
}
