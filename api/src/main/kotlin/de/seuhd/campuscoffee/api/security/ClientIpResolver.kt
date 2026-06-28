package de.seuhd.campuscoffee.api.security

import de.seuhd.campuscoffee.api.configuration.ClientIpStrategy
import de.seuhd.campuscoffee.api.configuration.LoginRateLimitProperties
import jakarta.servlet.http.HttpServletRequest
import org.springframework.stereotype.Component

/**
 * Resolves the originating client IP that the login rate limiter keys on, per the configured
 * [LoginRateLimitProperties.clientIpStrategy]. The default trusts only the socket peer; behind a proxy that
 * appends the real client to `X-Forwarded-For` (such as Google Cloud Run) it reads the trusted hop from the
 * right, so a client-supplied prefix cannot spoof the key.
 *
 * @property properties the rate-limit configuration carrying the IP strategy and the trusted-proxy hop count.
 */
@Component
class ClientIpResolver(
    private val properties: LoginRateLimitProperties
) {
    /**
     * Resolves the client IP from [request], reading `X-Forwarded-For` and the socket peer.
     *
     * @param request the servlet request to read the client address from.
     * @return the client IP to key the rate limiter on, or `"unknown"` when none can be determined.
     */
    fun clientIp(request: HttpServletRequest): String =
        clientIp(request.getHeader(FORWARDED_FOR_HEADER), request.remoteAddr)

    /**
     * Resolves the client IP from the raw `X-Forwarded-For` value and the socket peer. Under
     * [ClientIpStrategy.FORWARDED_FOR] it returns the entry [LoginRateLimitProperties.trustedProxyCount] hops
     * from the right of [forwardedForHeader] (the hop the outermost trusted proxy appended), which an attacker
     * cannot forge by prepending entries. Otherwise, or when the header is absent or the chain is shorter than
     * the trusted-hop count, it falls back to the unspoofable [remoteAddr].
     *
     * @param forwardedForHeader the `X-Forwarded-For` header value, or null when absent.
     * @param remoteAddr the direct socket peer address, or null when unavailable.
     * @return the resolved client IP, or `"unknown"` when neither source yields one.
     */
    internal fun clientIp(
        forwardedForHeader: String?,
        remoteAddr: String?
    ): String {
        if (properties.clientIpStrategy == ClientIpStrategy.FORWARDED_FOR && forwardedForHeader != null) {
            val hops = forwardedForHeader.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val index = hops.size - properties.trustedProxyCount
            if (index in hops.indices) {
                return hops[index]
            }
        }
        return remoteAddr?.takeIf { it.isNotEmpty() } ?: "unknown"
    }

    private companion object {
        private const val FORWARDED_FOR_HEADER = "X-Forwarded-For"
    }
}
