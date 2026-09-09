package de.seuhd.campuscoffee.api.app

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.core.io.ByteArrayResource
import org.springframework.core.io.DefaultResourceLoader
import org.springframework.core.io.Resource
import org.springframework.core.io.ResourceLoader

private const val SHELL = "<!doctype html><html><body><cc-root></cc-root></body></html>"

/** The `Accept` header a navigating browser sends. */
private const val BROWSER_ACCEPT = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"

/**
 * Which unmatched requests are answered with the single-page app's shell. Each case names a caller that
 * must keep the JSON body instead, because serving it a page would replace a clean error with markup it
 * cannot read.
 */
class SinglePageAppShellTest {
    private val shell = SinglePageAppShell(loaderWith(SHELL))

    @Test
    fun `shellFor returns the bundled shell for a browser navigation to an unknown page`() {
        assertThat(shell.shellFor("/nope", BROWSER_ACCEPT)).isEqualTo(SHELL)
        assertThat(shell.shellFor("/nope/deeper/still", BROWSER_ACCEPT)).isEqualTo(SHELL)
    }

    @ParameterizedTest
    @ValueSource(strings = ["/api", "/api/users/nope", "/actuator", "/actuator/nope", "/error"])
    fun `shellFor returns null for a backend path so an API client keeps the JSON body`(path: String) {
        assertThat(shell.shellFor(path, BROWSER_ACCEPT)).isNull()
    }

    @ParameterizedTest
    @ValueSource(strings = ["/main-ABC123.js", "/styles.css", "/assets/logo.png", "/robots.txt"])
    fun `shellFor returns null for a missing asset so it stays a 404 instead of becoming HTML`(path: String) {
        assertThat(shell.shellFor(path, BROWSER_ACCEPT)).isNull()
    }

    @ParameterizedTest
    @ValueSource(strings = ["*/*", "application/json", "image/png", "application/json, */*"])
    fun `shellFor returns null when the client did not ask for HTML`(accept: String) {
        assertThat(shell.shellFor("/nope", accept)).isNull()
    }

    @Test
    fun `shellFor returns null when the request sent no Accept header at all`() {
        assertThat(shell.shellFor("/nope", null)).isNull()
        assertThat(shell.shellFor("/nope", "")).isNull()
    }

    @Test
    fun `shellFor returns null for an unparseable Accept header rather than throwing`() {
        assertThat(shell.shellFor("/nope", "text/html;;;q=")).isNull()
    }

    @Test
    fun `shellFor returns null when no SPA is bundled, so a backend-only build keeps the JSON body`() {
        val backendOnly = SinglePageAppShell(DefaultResourceLoader())

        assertThat(backendOnly.shellFor("/nope", BROWSER_ACCEPT)).isNull()
    }

    /** A resource loader whose `static/index.html` holds [html] and whose every other path is absent. */
    private fun loaderWith(html: String): ResourceLoader =
        object : DefaultResourceLoader() {
            override fun getResource(location: String): Resource =
                if (location.endsWith("/static/index.html")) {
                    ByteArrayResource(html.toByteArray())
                } else {
                    super.getResource(location)
                }
        }
}
