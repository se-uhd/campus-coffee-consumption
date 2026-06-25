package de.seuhd.campuscoffee.api.security

import com.nimbusds.jose.EncryptionMethod
import com.nimbusds.jose.JWEAlgorithm
import com.nimbusds.jose.JWEHeader
import com.nimbusds.jose.JWEObject
import com.nimbusds.jose.Payload
import com.nimbusds.jose.crypto.RSAEncrypter
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import de.seuhd.campuscoffee.api.exceptions.LoginPayloadException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * Unit tests for [LoginPayloadDecryptor]: a fresh payload encrypted with the matching public key round-trips
 * to the credentials, every unreadable payload (not a JWE, encrypted with another key, a downgraded
 * algorithm, not a JSON object, or missing a credential field) is surfaced as a [LoginPayloadException], a
 * payload whose `iat` is missing or outside the freshness window (past or future) is rejected, and the same
 * ciphertext presented twice is rejected as a replay.
 */
class LoginPayloadDecryptorTest {
    private lateinit var key: RSAKey
    private lateinit var decryptor: LoginPayloadDecryptor
    private lateinit var seenFingerprints: MutableSet<String>

    private val now = Instant.parse("2026-06-24T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    @BeforeEach
    fun setUp() {
        key = RSAKeyGenerator(KEY_BITS).keyID("test").generate()
        seenFingerprints = mutableSetOf()
        // MutableSet.add returns true on first insert (first use) and false if already present (a replay)
        decryptor =
            LoginPayloadDecryptor(key.toRSAPrivateKey(), clock, Duration.ofMinutes(2)) { seenFingerprints.add(it) }
    }

    private fun encrypt(
        payload: Payload,
        withKey: RSAKey = key
    ): String {
        val header = JWEHeader.Builder(JWEAlgorithm.RSA_OAEP_256, EncryptionMethod.A256GCM).keyID(withKey.keyID).build()
        val jwe = JWEObject(header, payload)
        jwe.encrypt(RSAEncrypter(withKey.toRSAPublicKey()))
        return jwe.serialize()
    }

    /** A credentials payload with an `iat` (defaulting to the fixed clock's now), for the freshness check. */
    private fun credentials(
        loginName: String = "jane_doe",
        password: String? = "s3cret",
        iatMillis: Long? = now.toEpochMilli()
    ): Payload {
        val map =
            buildMap<String, Any> {
                put("loginName", loginName)
                password?.let { put("password", it) }
                iatMillis?.let { put("iat", it) }
            }
        return Payload(map)
    }

    @Test
    fun `decrypt returns the credentials from a fresh payload encrypted with the matching public key`() {
        val result = decryptor.decrypt(encrypt(credentials()))

        assertThat(result.loginName).isEqualTo("jane_doe")
        assertThat(result.password).isEqualTo("s3cret")
    }

    @Test
    fun `decrypt throws LoginPayloadException for a string that is not a JWE`() {
        assertThatThrownBy { decryptor.decrypt("not-a-jwe") }
            .isInstanceOf(LoginPayloadException::class.java)
    }

    @Test
    @Suppress("DEPRECATION") // RSA1_5 is used on purpose to verify the decryptor rejects an algorithm downgrade
    fun `decrypt throws LoginPayloadException for a JWE that uses a non-advertised algorithm`() {
        // a downgrade to RSA1_5 (which Nimbus's decrypter would otherwise accept) must be rejected
        val header = JWEHeader.Builder(JWEAlgorithm.RSA1_5, EncryptionMethod.A256GCM).keyID(key.keyID).build()
        val jwe = JWEObject(header, credentials())
        jwe.encrypt(RSAEncrypter(key.toRSAPublicKey()))

        assertThatThrownBy { decryptor.decrypt(jwe.serialize()) }
            .isInstanceOf(LoginPayloadException::class.java)
    }

    @Test
    fun `decrypt throws LoginPayloadException for a payload encrypted with a different key`() {
        val otherKey = RSAKeyGenerator(KEY_BITS).keyID("other").generate()

        assertThatThrownBy { decryptor.decrypt(encrypt(credentials(), otherKey)) }
            .isInstanceOf(LoginPayloadException::class.java)
    }

    @Test
    fun `decrypt throws LoginPayloadException when the decrypted body is not a JSON object`() {
        assertThatThrownBy { decryptor.decrypt(encrypt(Payload("not-json"))) }
            .isInstanceOf(LoginPayloadException::class.java)
    }

    @Test
    fun `decrypt throws LoginPayloadException when a credential field is missing`() {
        assertThatThrownBy { decryptor.decrypt(encrypt(credentials(password = null))) }
            .isInstanceOf(LoginPayloadException::class.java)
    }

    @Test
    fun `decrypt throws LoginPayloadException when the iat timestamp is missing`() {
        assertThatThrownBy { decryptor.decrypt(encrypt(credentials(iatMillis = null))) }
            .isInstanceOf(LoginPayloadException::class.java)
    }

    @Test
    fun `decrypt throws LoginPayloadException for a stale payload outside the freshness window`() {
        val stale = credentials(iatMillis = now.minus(Duration.ofMinutes(5)).toEpochMilli())

        assertThatThrownBy { decryptor.decrypt(encrypt(stale)) }
            .isInstanceOf(LoginPayloadException::class.java)
    }

    @Test
    fun `decrypt throws LoginPayloadException for a payload dated too far in the future`() {
        // a forward-skewed iat (beyond the window) is rejected just like a past one, so a pre-minted
        // ciphertext cannot be held and used later
        val future = credentials(iatMillis = now.plus(Duration.ofMinutes(5)).toEpochMilli())

        assertThatThrownBy { decryptor.decrypt(encrypt(future)) }
            .isInstanceOf(LoginPayloadException::class.java)
    }

    @Test
    fun `decrypt throws LoginPayloadException when the same ciphertext is presented a second time`() {
        val ciphertext = encrypt(credentials())
        // the first use succeeds; replaying the exact captured ciphertext within the window is rejected
        decryptor.decrypt(ciphertext)

        assertThatThrownBy { decryptor.decrypt(ciphertext) }
            .isInstanceOf(LoginPayloadException::class.java)
    }

    private companion object {
        private const val KEY_BITS = 2048
    }
}
