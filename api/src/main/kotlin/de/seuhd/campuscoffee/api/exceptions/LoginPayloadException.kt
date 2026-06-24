package de.seuhd.campuscoffee.api.exceptions

/**
 * Thrown when the encrypted login payload cannot be read: it is not a parseable JWE, it does not decrypt
 * with the configured private key, or its decrypted body is not the expected credentials JSON. The global
 * handler maps this to a 400 with a fixed, non-revealing message; it is deliberately distinct from a
 * wrong-credentials failure (which decrypts cleanly and then raises an authentication failure -> 401), so a
 * 400 leaks nothing about whether a login exists. This is an inbound web-security concern, so it lives in
 * the api layer rather than reusing a domain exception.
 *
 * @param cause the underlying parse, decryption, or deserialization failure (logged server-side only).
 */
class LoginPayloadException(
    cause: Throwable
) : RuntimeException("Malformed login payload.", cause)
