package de.seuhd.campuscoffee.api.security

/**
 * Records login-payload fingerprints so a captured ciphertext cannot be replayed within its freshness
 * window. The `iat` check already bounds the window; this makes a ciphertext single-use inside it.
 */
fun interface LoginReplayGuard {
    /**
     * Records [fingerprint] as seen and reports whether this was its first use.
     *
     * @param fingerprint a stable fingerprint of the encrypted payload (so the exact ciphertext is one-use).
     * @return true if the fingerprint had not been seen before (accept), false if it is a replay (reject).
     */
    fun isFirstUse(fingerprint: String): Boolean
}
