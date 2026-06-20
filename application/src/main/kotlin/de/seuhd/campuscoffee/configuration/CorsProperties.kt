package de.seuhd.campuscoffee.configuration

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * CORS configuration, bound from `campus-coffee.cors.*`. An escape hatch that stays **empty by default**:
 * the Angular SPA is bundled into the backend and served from the same origin (and the dev server proxies
 * `/api` to the backend), so the browser never makes a cross-origin API call and no CORS headers are
 * needed. If the SPA is ever hosted on a separate origin (e.g. a second Cloud Run service or Firebase
 * Hosting), add that origin here.
 *
 * @property allowedOrigins the origins permitted to make cross-origin API calls (empty disables CORS).
 */
@ConfigurationProperties("campus-coffee.cors")
data class CorsProperties(
    val allowedOrigins: List<String> = emptyList()
)
