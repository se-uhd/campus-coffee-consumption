package de.seuhd.campuscoffee.api.dtos

/**
 * Response body for `GET /api/auth/public-key`: the RSA public key the client uses to encrypt the login
 * payload, shaped as a JSON Web Key (JWK) so the browser can import it directly. Carries only public
 * material; the private key never leaves the server.
 *
 * @property kty the key type (`RSA`).
 * @property n the modulus, base64url-encoded.
 * @property e the public exponent, base64url-encoded.
 * @property alg the intended algorithm (`RSA-OAEP-256`).
 * @property use the intended use (`enc`, encryption).
 * @property kid the key id, echoed in the JWE header so the server knows which key a payload used.
 */
data class PublicKeyDto(
    val kty: String,
    val n: String,
    val e: String,
    val alg: String,
    val use: String,
    val kid: String
)
