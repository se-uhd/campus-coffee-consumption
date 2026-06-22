package de.seuhd.campuscoffee.api.capability

import de.seuhd.campuscoffee.api.configuration.AppProperties
import org.springframework.stereotype.Component

/**
 * Builds a member's capability URL — the secret "your coffee link" embedded in their QR code — from their
 * capability token and the configured public base URL. The token appears in a URL only here, at the SPA
 * entry point (`/login/{token}`); API calls forward it as the `X-Coffee-Token` header instead, keeping it
 * out of API access logs.
 */
@Component
class CapabilityUrlFactory(
    private val appProperties: AppProperties
) {
    /**
     * Assembles the capability URL for the given token: `"<base-url>/login/<token>"`.
     *
     * @param capabilityToken the member's secret capability token
     * @return the full capability URL to print as a QR code or show as the member's coffee link
     */
    fun urlFor(capabilityToken: String): String =
        "${appProperties.baseUrl.trimEnd('/')}$LOGIN_ROUTE_PREFIX$capabilityToken"

    private companion object {
        private const val LOGIN_ROUTE_PREFIX = "/login/"
    }
}
