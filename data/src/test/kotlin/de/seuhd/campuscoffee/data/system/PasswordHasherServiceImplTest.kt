package de.seuhd.campuscoffee.data.system

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Unit tests for [PasswordHasherServiceImpl], the data-layer adapter for the password-hashing port. The hash
 * must be opaque (not the raw password) and verifiable against the original password but not others.
 */
class PasswordHasherServiceImplTest {
    private val hasher = PasswordHasherServiceImpl()

    @Test
    fun `hash produces an encoder-prefixed value that is not the raw password`() {
        val hash = hasher.hash("correct horse battery staple")

        assertThat(hash).isNotEqualTo("correct horse battery staple")
        // the delegating encoder records the algorithm in a {id} prefix (e.g. {bcrypt})
        assertThat(hash).startsWith("{")
    }

    @Test
    fun `matches returns true for the original password and false otherwise`() {
        val hash = hasher.hash("s3cr3t-password")

        assertThat(hasher.matches("s3cr3t-password", hash)).isTrue()
        assertThat(hasher.matches("wrong-password", hash)).isFalse()
    }

    @Test
    fun `hashing the same password twice yields different salted hashes`() {
        val first = hasher.hash("repeatable")
        val second = hasher.hash("repeatable")

        // distinct salts make the two hashes differ, yet both still verify against the password
        assertThat(first).isNotEqualTo(second)
        assertThat(hasher.matches("repeatable", first)).isTrue()
        assertThat(hasher.matches("repeatable", second)).isTrue()
    }
}
