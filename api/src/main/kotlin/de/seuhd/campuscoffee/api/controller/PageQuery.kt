package de.seuhd.campuscoffee.api.controller

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Positive

/**
 * Reusable, validated query-parameter object for a paged read: an optional `limit` (`1..`[MAX_PAGE_LIMIT])
 * and a non-negative `offset`. It is bound from the query string and validated via `@Valid` during request
 * binding, raising the same 400 a malformed `@RequestBody` does, so it needs no class-level `@Validated`.
 *
 * That distinction is the whole point: `@Validated` switches on Bean Validation *method* validation, which a
 * controller extending the generic `CrudController` cannot use (its overridden `create`/`update` would trip
 * Hibernate Validator's HV000151, the Liskov rule on parameter constraints, and 500 every request). The
 * `@Valid` *binding* path has no such restriction, so this one object validates paging consistently on every
 * controller. Each endpoint keeps its own default page size via [limitOr]; an absent `limit` stays null and
 * skips the upper bound.
 *
 * @property limit  the requested page size, or null to use the endpoint's default; must be `1..`[MAX_PAGE_LIMIT]
 * @property offset the number of newest entries to skip; must not be negative
 */
data class PageQuery(
    @field:Positive
    @field:Max(MAX_PAGE_LIMIT)
    val limit: Int? = null,
    @field:Min(0)
    val offset: Int = 0
) {
    /**
     * The requested limit, or [default] when the caller supplied none.
     *
     * @param default the endpoint's own default page size
     */
    fun limitOr(default: Int): Int = limit ?: default

    companion object {
        /** The maximum page size any paged read accepts; an out-of-range value is a 400, not a silent clamp. */
        const val MAX_PAGE_LIMIT = 100L
    }
}
