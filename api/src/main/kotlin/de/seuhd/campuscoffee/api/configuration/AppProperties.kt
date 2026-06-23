package de.seuhd.campuscoffee.api.configuration

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Application-level properties for assembling member-facing URLs.
 *
 * [baseUrl] is the public origin the app is reached at (scheme + host, e.g. the Cloud Run URL in prod or
 * `http://localhost:8080` in dev). It is the base for a member's capability URL (the secret link encoded
 * in their QR code), which is `"$baseUrl/login/{token}"`. Per the W3C capability URL good practices the
 * base must be `https` in production so the token never travels over plain HTTP.
 */
@ConfigurationProperties("campus-coffee.app")
data class AppProperties(
    val baseUrl: String = "http://localhost:8080"
)
