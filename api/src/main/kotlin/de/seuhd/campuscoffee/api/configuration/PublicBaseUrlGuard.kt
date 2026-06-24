package de.seuhd.campuscoffee.api.configuration

import jakarta.annotation.PostConstruct
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

/**
 * Fails startup fast on an unsafe public base URL. The capability token is embedded in the QR/login URL built
 * from [AppProperties.baseUrl], so an internet-facing deployment must point that base at a real https origin:
 * a missing value (the deploy forgot to set `CAMPUS_COFFEE_APP_BASE_URL`) or a plain-http/localhost value
 * would print dead or token-leaking links on the wall QR codes. This mirrors the fail-fast that
 * `JwtProperties` does for the signing secret. It is `@Profile("prod", "demo")` (the two internet-facing
 * profiles), so local dev and the tests (which use `http://localhost:8080`) are unaffected.
 *
 * @property appProperties the application URL properties whose base URL is validated
 */
@Configuration
@Profile("prod", "demo")
class PublicBaseUrlGuard(
    private val appProperties: AppProperties
) {
    /** Rejects a blank, non-https, or localhost base URL once the context is built, before serving traffic. */
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
    }
}
