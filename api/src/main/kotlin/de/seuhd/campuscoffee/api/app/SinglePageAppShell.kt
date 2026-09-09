package de.seuhd.campuscoffee.api.app

import org.springframework.core.io.ResourceLoader
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets

/** Where the bundled SPA's entry document lives on the classpath once the frontend build has run. */
private const val SHELL_LOCATION = "classpath:/static/index.html"

/** The path prefixes that belong to the backend and must answer for themselves, never with the SPA shell. */
private val BACKEND_PREFIXES = listOf("/api", "/actuator", "/error")

/**
 * The single-page application's entry document, served for a browser navigation that matched no handler and
 * no static file.
 *
 * [SinglePageAppController] forwards the routes the SPA is known to own. Everything else used to end at the
 * API's JSON 404, so a visitor who mistyped a link, or followed a stale one, was shown an error body in the
 * browser window instead of the app's own not-found page, which exists for exactly that case. This fills
 * that gap from the other end: the decision is made only once Spring has found neither a handler nor a
 * static resource, so it can never shadow an API path or an asset that does exist.
 *
 * Three conditions have to hold, and each excludes a caller that wants the JSON:
 *
 * - the path is not the backend's own (`/api`, `/actuator`, `/error`), so a mistyped endpoint still answers
 *   an API client in the format it asked for rather than with a page it cannot read;
 * - the last path segment carries no extension, so a missing script or image stays a 404 rather than
 *   resolving to HTML that the browser would then fail to parse as the asset it asked for;
 * - the client actually asked for HTML specifically, which a navigating browser does and `fetch`, `curl`
 *   and an image request, which all send a wildcard, do not.
 *
 * The status stays 404. The document is the app, but the URL genuinely does not exist, and the page the SPA
 * routes to says so; answering 200 would tell a crawler that every mistyped path is a real one.
 *
 * A backend-only build has no bundled SPA (the frontend build is wired to `bootJar`/`bootRun`, so a bare
 * `gradle test` never produces one). The shell is then absent and every path falls back to the JSON 404,
 * which is what the system tests see.
 */
@Component
class SinglePageAppShell(
    resourceLoader: ResourceLoader
) {
    private val html: String? =
        resourceLoader
            .getResource(SHELL_LOCATION)
            .takeIf { it.exists() }
            ?.getContentAsString(StandardCharsets.UTF_8)

    /**
     * The SPA shell to answer an unmatched request with, or null to leave it to the API's JSON 404.
     *
     * @param path the request path that matched nothing
     * @param acceptHeader the request's `Accept` header, or null when it sent none
     */
    fun shellFor(
        path: String,
        acceptHeader: String?
    ): String? = html?.takeIf { isNavigationToAPageThisAppOwns(path, acceptHeader) }

    /** Whether [path] with [acceptHeader] is a browser asking for a page rather than a client asking for data. */
    private fun isNavigationToAPageThisAppOwns(
        path: String,
        acceptHeader: String?
    ): Boolean = !isBackendPath(path) && !hasFileExtension(path) && wantsHtml(acceptHeader)

    /** Whether [path] belongs to the backend, which answers for itself. */
    private fun isBackendPath(path: String): Boolean = BACKEND_PREFIXES.any { path == it || path.startsWith("$it/") }

    /** Whether the last segment of [path] looks like a file, which is a static asset that simply is not there. */
    private fun hasFileExtension(path: String): Boolean = path.substringAfterLast('/').contains('.')

    /**
     * Whether [acceptHeader] asks for HTML specifically. A wildcard does not count: it is what a script, an
     * image and `curl` send, and answering those with a page would replace a clean 404 with markup.
     */
    private fun wantsHtml(acceptHeader: String?): Boolean =
        // The null check is what smart-casts acceptHeader for the parse below. A blank header needs no check
        // of its own: it parses to no media types at all, so it answers false through the same path.
        acceptHeader != null &&
            runCatching { MediaType.parseMediaTypes(acceptHeader) }
                .getOrDefault(emptyList())
                .any { !it.equalsTypeAndSubtype(MediaType.ALL) && it.includes(MediaType.TEXT_HTML) }
}
