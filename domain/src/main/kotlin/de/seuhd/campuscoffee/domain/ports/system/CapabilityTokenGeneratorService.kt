package de.seuhd.campuscoffee.domain.ports.system

/**
 * Generates the secret capability token a user authenticates with. A port in the hexagonal
 * architecture, declared by the domain and implemented by the data layer. Following the W3C capability-URL
 * good practices, a production token is a high-entropy, unguessable random value (not a sequential id);
 * the data adapter decides the exact scheme (e.g. 256-bit, base64url-encoded).
 */
fun interface CapabilityTokenGeneratorService {
    /** Returns a fresh, unguessable capability token. */
    fun newToken(): String
}
