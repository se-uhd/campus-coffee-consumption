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

/**
 * Unit tests for [LoginPayloadDecryptor]: a payload encrypted with the matching public key round-trips to
 * the credentials, and every unreadable payload (not a JWE, encrypted with another key, not a JSON object,
 * or missing a credential field) is surfaced as a [LoginPayloadException].
 */
class LoginPayloadDecryptorTest {
    private lateinit var key: RSAKey
    private lateinit var decryptor: LoginPayloadDecryptor

    @BeforeEach
    fun setUp() {
        key = RSAKeyGenerator(KEY_BITS).keyID("test").generate()
        decryptor = LoginPayloadDecryptor(key.toRSAPrivateKey())
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

    @Test
    fun `decrypt returns the credentials from a payload encrypted with the matching public key`() {
        val jwe = encrypt(Payload(mapOf<String, Any>("loginName" to "jane_doe", "password" to "s3cret")))

        val credentials = decryptor.decrypt(jwe)

        assertThat(credentials.loginName).isEqualTo("jane_doe")
        assertThat(credentials.password).isEqualTo("s3cret")
    }

    @Test
    fun `decrypt throws LoginPayloadException for a string that is not a JWE`() {
        assertThatThrownBy { decryptor.decrypt("not-a-jwe") }
            .isInstanceOf(LoginPayloadException::class.java)
    }

    @Test
    fun `decrypt throws LoginPayloadException for a JWE that uses a non-advertised algorithm`() {
        // a downgrade to RSA1_5 (which Nimbus's decrypter would otherwise accept) must be rejected
        val header = JWEHeader.Builder(JWEAlgorithm.RSA1_5, EncryptionMethod.A256GCM).keyID(key.keyID).build()
        val jwe = JWEObject(header, Payload(mapOf<String, Any>("loginName" to "jane_doe", "password" to "s3cret")))
        jwe.encrypt(RSAEncrypter(key.toRSAPublicKey()))

        assertThatThrownBy { decryptor.decrypt(jwe.serialize()) }
            .isInstanceOf(LoginPayloadException::class.java)
    }

    @Test
    fun `decrypt throws LoginPayloadException for a payload encrypted with a different key`() {
        val otherKey = RSAKeyGenerator(KEY_BITS).keyID("other").generate()
        val jwe = encrypt(Payload(mapOf<String, Any>("loginName" to "jane_doe", "password" to "s3cret")), otherKey)

        assertThatThrownBy { decryptor.decrypt(jwe) }
            .isInstanceOf(LoginPayloadException::class.java)
    }

    @Test
    fun `decrypt throws LoginPayloadException when the decrypted body is not a JSON object`() {
        val jwe = encrypt(Payload("not-json"))

        assertThatThrownBy { decryptor.decrypt(jwe) }
            .isInstanceOf(LoginPayloadException::class.java)
    }

    @Test
    fun `decrypt throws LoginPayloadException when a credential field is missing`() {
        val jwe = encrypt(Payload(mapOf<String, Any>("loginName" to "jane_doe")))

        assertThatThrownBy { decryptor.decrypt(jwe) }
            .isInstanceOf(LoginPayloadException::class.java)
    }

    private companion object {
        private const val KEY_BITS = 2048
    }
}
