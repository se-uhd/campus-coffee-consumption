package de.seuhd.campuscoffee.configuration

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.web.cors.UrlBasedCorsConfigurationSource

/**
 * Unit tests for the CORS escape hatch: empty by default (no cross-origin access), and a registered
 * mapping when origins are configured.
 */
class CorsConfigTest {
    private val config = CorsConfig()

    @Test
    fun `an empty allowlist registers no CORS mapping`() {
        val source = config.corsConfigurationSource(CorsProperties(emptyList())) as UrlBasedCorsConfigurationSource

        assertThat(source.corsConfigurations).isEmpty()
    }

    @Test
    fun `a configured origin registers a mapping for the API paths`() {
        val source =
            config.corsConfigurationSource(
                CorsProperties(listOf("https://app.example"))
            ) as UrlBasedCorsConfigurationSource

        assertThat(source.corsConfigurations).containsKey("/api/**")
        assertThat(source.corsConfigurations["/api/**"]!!.allowedOrigins).containsExactly("https://app.example")
    }
}
