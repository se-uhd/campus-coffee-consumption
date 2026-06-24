package de.seuhd.campuscoffee.api.dtos

import jakarta.validation.constraints.NotBlank

/**
 * Request body for `POST /api/auth/token`: the encrypted credentials to exchange for a bearer token. The
 * client encrypts `{ loginName, password }` with the published RSA public key (`GET /api/auth/public-key`)
 * as a compact JWE, so the raw credentials never travel as plaintext past a TLS-terminating proxy or into
 * request-body logs.
 *
 * @property encryptedPayload the compact-serialized JWE carrying the credentials; required.
 */
data class TokenRequestDto(
    @field:NotBlank(message = "Encrypted payload cannot be empty.")
    val encryptedPayload: String?
)
