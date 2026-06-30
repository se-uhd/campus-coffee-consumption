package de.seuhd.campuscoffee.api.configuration

import jakarta.annotation.PostConstruct
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

/**
 * Fails startup fast on an unsafe public base URL. The capability token is embedded in the QR/login URL built
 * from [AppProperties.baseUrl], so an internet-facing deployment must point that base at a real, public https
 * origin: a missing value (the deploy forgot to set `CAMPUS_COFFEE_APP_BASE_URL`), a plain-http value, or a
 * loopback/bare-hostname value (`https://localhost`, `https://127.0.0.1`, `https://app`) would print dead or
 * token-leaking links on the wall QR codes. This mirrors the fail-fast that `JwtProperties` does for the
 * signing secret. It is `@Profile("prod")`, so local dev (`http://localhost:8081`) and the tests
 * (`http://localhost:8080`) are unaffected.
 *
 * @property appProperties the application URL properties whose base URL is validated
 */
@Configuration
@Profile("prod")
class PublicBaseUrlGuard(
    private val appProperties: AppProperties
) {
    /**
     * Rejects a blank, non-https, loopback, or bare-hostname base URL once the context is built, before
     * serving traffic.
     */
    @PostConstruct
    fun validateBaseUrl() {
        val baseUrl = appProperties.baseUrl.trim()
        require(baseUrl.isNotEmpty()) {
            "campus-coffee.app.base-url must be set in a public deployment (the https origin for capability " +
                "URLs); set CAMPUS_COFFEE_APP_BASE_URL."
        }
        require(baseUrl.startsWith("https://")) {
            "campus-coffee.app.base-url must be https in a public deployment (the capability token is embedded " +
                "in the URL); got '$baseUrl'. Set CAMPUS_COFFEE_APP_BASE_URL to the public https URL."
        }
        val host = hostOf(baseUrl)
        require(host !in LOOPBACK_HOSTS && host.contains('.')) {
            "campus-coffee.app.base-url must be a public host in a public deployment (not localhost/loopback " +
                "or a bare hostname); got '$baseUrl'. The capability QR and login links are built from it, so a " +
                "non-public host would render unreachable codes. Set CAMPUS_COFFEE_APP_BASE_URL to the public URL."
        }
    }

    /**
     * Extracts the lowercased host from an `https://` base URL, dropping any port and IPv6 brackets.
     *
     * @param url the trimmed base URL, already known to start with `https://`.
     */
    private fun hostOf(url: String): String {
        val authority = url.removePrefix("https://").substringBefore('/')
        return if (authority.startsWith("[")) {
            authority.substringAfter('[').substringBefore(']').lowercase()
        } else {
            authority.substringBefore(':').lowercase()
        }
    }

    private companion object {
        private val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "0.0.0.0", "::1")
    }
}
