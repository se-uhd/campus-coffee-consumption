package de.seuhd.campuscoffee.domain.ports.data

/**
 * Port for hashing and verifying user passwords.
 *
 * Defined in the domain so the user service can hash a raw password without the domain importing Spring
 * Security; the data layer supplies the adapter (a BCrypt-backed implementation).
 */
interface PasswordHasher {
    /**
     * Hashes a raw password, returning an opaque, storable representation (a salted hash with an
     * encoder prefix).
     *
     * @param rawPassword the plaintext password to hash
     * @return the hash to persist
     */
    fun hash(rawPassword: String): String

    /**
     * Verifies a raw password against a previously stored hash.
     *
     * @param rawPassword the plaintext password to check
     * @param storedHash  the hash produced by [hash]
     * @return true if the password matches the hash
     */
    fun matches(
        rawPassword: String,
        storedHash: String
    ): Boolean
}
