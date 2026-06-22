package de.seuhd.campuscoffee.configuration

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource

/**
 * Builds the CORS source the security chain consults. With an empty [CorsProperties.allowedOrigins] (the
 * default, same-origin SPA) it registers no path mapping, so a cross-origin request receives no
 * `Access-Control-Allow` headers, i.e. CORS stays off. When origins are configured, it allows them on
 * the API paths with the headers the SPA needs (`Authorization`, `X-Coffee-Token`, `Content-Type`).
 */
@Configuration
class CorsConfig {
    /**
     * The CORS source for the security chain: empty (no cross-origin access) unless origins are configured.
     *
     * @param corsProperties the configured allowlist (empty by default)
     * @return the CORS configuration source
     */
    @Bean
    fun corsConfigurationSource(corsProperties: CorsProperties): CorsConfigurationSource {
        val source = UrlBasedCorsConfigurationSource()
        if (corsProperties.allowedOrigins.isNotEmpty()) {
            val configuration =
                CorsConfiguration().apply {
                    allowedOrigins = corsProperties.allowedOrigins
                    allowedMethods = listOf("GET", "POST", "PUT", "DELETE", "OPTIONS")
                    allowedHeaders = listOf("Authorization", "X-Coffee-Token", "Content-Type")
                }
            source.registerCorsConfiguration("/api/**", configuration)
        }
        return source
    }
}
