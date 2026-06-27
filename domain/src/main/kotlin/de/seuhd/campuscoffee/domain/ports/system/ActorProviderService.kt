package de.seuhd.campuscoffee.domain.ports.system

/**
 * Supplies the login name of the actor responsible for the current change, recorded as `created_by` on
 * each event. A port in the hexagonal architecture: declared by the domain and implemented in the
 * api layer, which reads the authenticated principal from Spring Security's `SecurityContext`
 * (the user via their capability token, or the admin). When there is no request principal (startup
 * fixtures, the bootstrap admin, an events-to-data rebuild), the adapter returns `"SYSTEM"`.
 *
 * `created_by` is intentionally a login string rather than a user id: it is audit metadata shown to
 * humans, it represents the non-user `"SYSTEM"` actor naturally, and an append-only log must not
 * foreign key into the mutable users read model.
 */
fun interface ActorProviderService {
    /** Returns the current actor's login name, or `"SYSTEM"` when there is no authenticated principal. */
    fun currentActor(): String
}
