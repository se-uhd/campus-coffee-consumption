package de.seuhd.campuscoffee.api.security

import com.nimbusds.jose.EncryptionMethod
import com.nimbusds.jose.JOSEException
import com.nimbusds.jose.JWEAlgorithm
import com.nimbusds.jose.JWEHeader
import com.nimbusds.jose.JWEObject
import com.nimbusds.jose.crypto.RSADecrypter
import de.seuhd.campuscoffee.api.exceptions.LoginPayloadException
import java.security.interfaces.RSAPrivateKey
import java.text.ParseException
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * The credentials carried inside the encrypted login payload. This is an internal type (never a wire DTO):
 * the client serializes it as the JWE plaintext, and the controller hands it straight to the
 * authentication manager.
 *
 * @property loginName the admin's login name.
 * @property password the admin's password.
 */
data class LoginCredentials(
    val loginName: String,
    val password: String
)

/**
 * Decrypts the compact JWE that a client sends to the token endpoint, recovering the login credentials.
 * The frontend encrypts `{ loginName, password, iat }` with the published RSA public key (`alg=RSA-OAEP-256`,
 * `enc=A256GCM`); only the configured private key here can decrypt it. Any failure to parse, decrypt, or
 * read the credentials is surfaced as a single [LoginPayloadException] (a 400), keeping a malformed payload
 * distinct from wrong-but-readable credentials (a 401).
 *
 * The `iat` (client-set epoch-millis timestamp) gives the ciphertext a short freshness window: a payload
 * whose `iat` is further than [maxPayloadAge] from the server clock (in either direction, for skew) is
 * rejected, so a captured ciphertext cannot be replayed indefinitely against the token endpoint. A stale
 * payload is a [LoginPayloadException] (400), the same as any other unreadable payload, so it is not a
 * credential oracle.
 *
 * @param privateKey the RSA private key matching the published public key.
 * @param clock the clock the payload freshness is checked against.
 * @param maxPayloadAge the maximum allowed difference between the payload's `iat` and the server clock.
 */
class LoginPayloadDecryptor(
    private val privateKey: RSAPrivateKey,
    private val clock: Clock,
    private val maxPayloadAge: Duration
) {
    /**
     * Decrypts and parses the compact JWE into the login credentials, rejecting a stale payload.
     *
     * @param compactJwe the compact-serialized JWE sent by the client.
     * @return the decrypted login credentials.
     */
    fun decrypt(compactJwe: String): LoginCredentials =
        try {
            val jwe = JWEObject.parse(compactJwe)
            requireAdvertisedAlgorithms(jwe.header)
            jwe.decrypt(RSADecrypter(privateKey))
            val json = jwe.payload.toJSONObject() ?: throw notJsonObject()
            requireFresh(json["iat"])
            LoginCredentials(
                loginName = json["loginName"] as? String ?: missingField("loginName"),
                password = json["password"] as? String ?: missingField("password")
            )
        } catch (e: ParseException) {
            throw LoginPayloadException(e)
        } catch (e: JOSEException) {
            throw LoginPayloadException(e)
        }

    /**
     * Rejects a payload whose `iat` is missing, unreadable, or further than [maxPayloadAge] from the server
     * clock (in either direction), bounding the replay window of a captured ciphertext.
     *
     * @param rawIat the `iat` value read from the decrypted JSON (expected: epoch milliseconds).
     */
    private fun requireFresh(rawIat: Any?) {
        val iatMillis = (rawIat as? Number)?.toLong() ?: missingField("iat")
        val skew = Duration.between(Instant.ofEpochMilli(iatMillis), clock.instant()).abs()
        if (skew > maxPayloadAge) {
            throw LoginPayloadException(IllegalArgumentException("login payload is stale (iat skew $skew)"))
        }
    }

    /**
     * Rejects a JWE that does not use exactly the advertised key-management and content-encryption pair
     * (`RSA-OAEP-256` + `A256GCM`), so a client cannot downgrade to a weaker RSA algorithm (e.g. RSA1_5 or
     * RSA-OAEP with SHA-1) that Nimbus's decrypter would otherwise still accept. Rejection happens before any
     * RSA private-key operation runs.
     *
     * @param header the parsed JWE header to check.
     */
    private fun requireAdvertisedAlgorithms(header: JWEHeader) {
        if (header.algorithm != JWEAlgorithm.RSA_OAEP_256 || header.encryptionMethod != EncryptionMethod.A256GCM) {
            throw LoginPayloadException(
                IllegalArgumentException(
                    "unexpected JWE algorithms: ${header.algorithm} / ${header.encryptionMethod}"
                )
            )
        }
    }

    /** The exception for a decrypted body that is not a JSON object. */
    private fun notJsonObject(): LoginPayloadException =
        LoginPayloadException(IllegalArgumentException("login payload is not a JSON object"))

    /**
     * Fails with a [LoginPayloadException] for a missing or non-string credential field.
     *
     * @param name the name of the absent credential field.
     */
    private fun missingField(name: String): Nothing =
        throw LoginPayloadException(IllegalArgumentException("login payload field '$name' is missing or not a string"))
}
