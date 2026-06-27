package de.seuhd.campuscoffee.api.controller

import de.seuhd.campuscoffee.api.dtos.GlobalActivityEntryDto
import de.seuhd.campuscoffee.api.mapper.AccountingDtoMapper
import de.seuhd.campuscoffee.api.security.CurrentUserProvider
import de.seuhd.campuscoffee.api.support.ActivityCsvResponder
import de.seuhd.campuscoffee.domain.ports.api.ActivityService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springdoc.core.annotations.ParameterObject
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody

/**
 * Admin read controller for the whole-installation activity feed (JWT, admin only): a paginated table of every
 * change across all users, the kitty, and the price, and a full CSV export of the same. It lives under
 * `/api/users` (already the admin-gated prefix, so no extra security rule is needed) as the all-users
 * analogue of `GET /api/users/{id}/activity`; Spring matches the literal `/activity` ahead of `/{id}`. Paging
 * is validated through the shared [PageQuery] via `@Valid` binding, never a class-level `@Validated` (which
 * would trip HV000151 on a paginated read).
 */
@Tag(name = "Admin activity", description = "The whole-installation activity feed and its CSV export (admin only).")
@Controller
@RequestMapping("/users")
class AdminActivityController(
    private val activityService: ActivityService,
    private val accountingDtoMapper: AccountingDtoMapper,
    private val activityCsvResponder: ActivityCsvResponder,
    private val currentUserProvider: CurrentUserProvider
) {
    /**
     * Returns a page of the whole-installation activity feed (every user's coffees, purchases, and deposits,
     * the kitty adjustments, and price changes), newest first, each row carrying the subject user, the
     * actor, and the user and kitty running balances the event moved.
     *
     * @param page the validated paging window (limit/offset)
     */
    @Operation(summary = "Get a page of the whole-installation activity feed.")
    @GetMapping("/activity")
    fun activity(
        @Valid @ParameterObject page: PageQuery
    ): ResponseEntity<List<GlobalActivityEntryDto>> {
        val admin = currentUserProvider.currentUser()
        return ResponseEntity.ok(
            accountingDtoMapper.toGlobalEntryDtos(
                activityService.globalActivity(page.limitOr(DEFAULT_LIMIT), page.offset, admin)
            )
        )
    }

    /**
     * Streams the entire whole-installation activity feed as a CSV download (`activity.csv`), unpaged and
     * newest first, for spreadsheets and record-keeping.
     */
    @Operation(summary = "Download the whole-installation activity feed as CSV.")
    @GetMapping("/activity.csv", produces = ["text/csv"])
    fun activityCsv(): ResponseEntity<StreamingResponseBody> {
        val admin = currentUserProvider.currentUser()
        return activityCsvResponder.csvResponse(activityService.globalActivityForExport(admin))
    }

    private companion object {
        /** The default page size for the global activity read when the caller supplies no limit. */
        private const val DEFAULT_LIMIT = 20
    }
}
