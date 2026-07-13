package de.seuhd.campuscoffee.api.dtos

/**
 * Response body for `GET /api/users/me/totp/status`: whether the acting admin has completed second-factor
 * enrollment. The SPA reads this on every admin-route entry so a not-yet-enrolled admin is routed to the
 * enrollment page even after a page reload or a deep link.
 *
 * @property enrolled true when the admin has an active second factor
 */
data class TotpStatusDto(
    val enrolled: Boolean
)
