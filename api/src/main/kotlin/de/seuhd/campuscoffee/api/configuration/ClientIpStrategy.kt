package de.seuhd.campuscoffee.api.configuration

/**
 * How the login rate limiter derives the client IP it keys on. Selected by
 * `campus-coffee.auth.rate-limit.client-ip-strategy`. Vendor-independent: a specific platform (e.g. Google
 * Cloud Run) is expressed as configuration, not a dedicated code path.
 *
 * To support an edge/CDN that sets a single dedicated client-IP header the client cannot forge (e.g.
 * Cloudflare's `CF-Connecting-IP` or Akamai's `True-Client-IP`), add a third value `TRUSTED_HEADER` here,
 * plus a `trustedHeaderName` property on [LoginRateLimitProperties] and a matching branch in
 * `ClientIpResolver` that reads that one header verbatim instead of counting `X-Forwarded-For` hops. Google
 * Cloud Run has no such header, so this is not needed today; it is the genuinely vendor-specific shape if the
 * app ever runs behind a dedicated-header CDN.
 */
enum class ClientIpStrategy {
    /**
     * Trust only the direct socket peer (`remoteAddr`) and ignore `X-Forwarded-For`. The safe default for a
     * non-proxied deployment: the header cannot spoof the key, though every client behind a proxy collapses
     * to the proxy's IP.
     */
    REMOTE_ADDR,

    /**
     * Read the client IP from `X-Forwarded-For`, taking the entry
     * [LoginRateLimitProperties.trustedProxyCount] hops from the right (the hop the outermost trusted proxy
     * appended). Use behind a proxy that appends the real client IP, such as Google Cloud Run.
     */
    FORWARDED_FOR
}
