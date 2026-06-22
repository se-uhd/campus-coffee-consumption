package de.seuhd.campuscoffee.web

import io.swagger.v3.oas.annotations.Hidden
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping

/**
 * Forwards the Angular SPA's client-side routes to its `index.html` so a deep link survives a page refresh.
 * The routes (`/login/:token` and its profile, the admin landing `/admin`, the other `/admin` pages such as
 * `/admin/login`, `/admin/users`, and `/admin/profile`) are handled by the Angular router in the browser;
 * on a full page load the server must still return the SPA shell for them rather than a 404. The root `/`
 * and any other path are redirected to `/admin` by the Angular router client-side once the shell loads. The
 * routes are listed explicitly so this never shadows the API paths, the actuator, the API docs, or a static
 * asset (which carries a file extension and is served directly).
 *
 * Lives outside the `api.controller` package so the central `/api` base path is not applied to it.
 */
@Hidden
@Controller
class SpaForwardingController {
    /** Forwards a recognized SPA route to the bundled `index.html`. */
    @Suppress("FunctionOnlyReturningConstant") // a Spring MVC forward handler must return the view name
    @GetMapping(
        value = [
            "/",
            "/admin/**"
        ]
    )
    fun forwardToIndex(): String = "forward:/index.html"

    /**
     * Forwards the capability (`/login/:token`) routes to the SPA shell, additionally sending
     * `X-Robots-Tag: noindex` so the secret token page is never indexed (it pairs with the `Disallow:
     * /login/` in `robots.txt`, per the W3C capability URL good practices).
     *
     * @param response the servlet response the no-index header is set on
     */
    @GetMapping("/login/**")
    fun forwardCapabilityPage(response: HttpServletResponse): String {
        response.setHeader("X-Robots-Tag", "noindex")
        return "forward:/index.html"
    }
}
