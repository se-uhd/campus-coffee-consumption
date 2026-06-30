package de.seuhd.campuscoffee.api.configuration

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Application-level properties for assembling user-facing URLs.
 *
 * [baseUrl] is the public origin the app is reached at (scheme + host, e.g. the Cloud Run URL in prod; the
 * dev profile sets `http://localhost:8081` in `application.yaml`). It is the base for a user's capability URL
 * (the secret link encoded in their QR code), which is `"$baseUrl/login/{token}"`. Per the W3C capability URL
 * good practices the base must be `https` in production so the token never travels over plain HTTP.
 *
 * The default below is only a fallback for a completely unset property; in practice `application.yaml` always
 * supplies the value: its global block sets `http://localhost:8080` for the no-profile tests, the dev profile
 * `http://localhost:8081`, and prod the public https origin.
 */
@ConfigurationProperties("campus-coffee.app")
data class AppProperties(
    val baseUrl: String = "http://localhost:8080"
)
