# Encrypting the admin login payload

The admin login (`POST /api/auth/token`) used to send `{ loginName, password }` as plaintext JSON. TLS
already protects that on the wire (prod requires `https`), so this change is **defense in depth**: keep the
raw password from ever appearing as plaintext at a TLS-terminating reverse proxy or load balancer (Cloud
Run's front end) or in any request-body logging that sits behind TLS termination.

## Why not a shared symmetric key

The first idea was a symmetric key shared between the frontend and the backend. That was rejected. The
frontend is a browser SPA, so any key bundled into its JavaScript is readable by anyone who opens DevTools
or fetches the bundle. A "shared secret" that ships to every client is not a secret, so symmetric encryption
there is obfuscation, not security, and adds nothing over TLS.

## The mechanism: asymmetric RSA, as a JWE

The backend holds an RSA private key and publishes only the **public** key. The browser encrypts the
credentials with that public key; only the backend can decrypt. No secret lives in the JS bundle.

- **Format.** A compact JWE with `alg=RSA-OAEP-256` and `enc=A256GCM`. This is a hybrid scheme (RSA wraps a
  random AES content key), so there is no RSA payload-size limit and no hand-rolled crypto envelope. Nimbus
  JOSE is already on the backend classpath (it powers the JWT signing), so decryption is a few lines and
  needs no new dependency; the SPA uses the standard `jose` library (lazy-loaded, so it stays out of the
  initial bundle).
- **Public-key endpoint.** `GET /api/auth/public-key` returns the public key as a JWK
  (`kty, n, e, alg=RSA-OAEP-256, use=enc, kid`), reachable without authentication. The SPA fetches it, then
  encrypts `{ loginName, password, iat }` and posts `{ encryptedPayload }`.
- **Algorithm pinning.** The decryptor accepts only the advertised `RSA-OAEP-256` + `A256GCM` pair and
  rejects anything else before any RSA private-key operation runs. A bare Nimbus `RSADecrypter` otherwise
  also accepts `RSA1_5` and `RSA-OAEP` (SHA-1); we do not advertise those, so we do not accept them.
- **Error handling.** A payload that cannot be parsed, decrypted, or read returns **400** with a fixed,
  non-revealing message (the cause is logged server-side only), so the response is not a decryption oracle.
  This is deliberately distinct from the **401** a payload that decrypts cleanly but carries wrong
  credentials gets, and a 400 reveals nothing about whether a login exists.

This stays entirely in the **api** layer (a `LoginEncryptionConfig`, `LoginPayloadDecryptor`,
`LoginEncryptionProperties`, and a `PublicKeyDto`) plus the SPA. There are no domain, data, Flyway, or
event-log changes.

## Key management

The key is **configured, not generated per startup**, and must be identical on every instance: a client may
fetch the public key from one instance and post the ciphertext to another, so an in-memory per-instance key
would fail intermittently under horizontal scaling.

- It mirrors the JWT secret: `campus-coffee.login-encryption.private-key-pem` (a PKCS#8 PEM, at least 2048
  bits), required in prod via `LOGIN_PRIVATE_KEY_PEM` (Secret Manager) with an insecure committed dev
  fallback. It is parsed with Spring Security's `RsaKeyConverters` rather than hand-rolled base64 decoding.
- An `env_file` value cannot span lines, so the prod key is delivered as one line with literal `\n`
  separators, which the config restores to real newlines before parsing.

## What this does and does not protect

- **Does:** keep `loginName` and `password` as ciphertext at TLS-terminating proxies and load balancers and
  in request-body logs; only the backend private key decrypts.
- **Does not:** replace TLS (still required); defend against a compromised client or XSS (a malicious script
  reads the password from the form before encryption); make the login zero-knowledge (the server decrypts to
  plaintext and still verifies the password with bcrypt, by design, since it must verify it).
- **Replay:** the payload carries an `iat` (client-set epoch millis), and the decryptor rejects one whose
  `iat` is further than `campus-coffee.login-encryption.max-payload-age` (default 2 minutes, in either
  direction for clock skew) from the server clock, so a captured ciphertext cannot be replayed beyond that
  window. Within the window, the decryptor also fingerprints each ciphertext and rejects a second
  presentation of the same one, so a captured ciphertext is single-use. The nonce state is in-memory and per
  instance, so on a multi-instance deployment the per-instance freshness window still bounds a cross-instance
  replay.
