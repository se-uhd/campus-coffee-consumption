package de.seuhd.campuscoffee.api.controller

import de.seuhd.campuscoffee.api.dtos.ActivityEntryDto
import de.seuhd.campuscoffee.api.dtos.UserBalanceDto
import de.seuhd.campuscoffee.api.dtos.UserSummaryDto
import de.seuhd.campuscoffee.api.mapper.AccountingDtoMapper
import de.seuhd.campuscoffee.api.security.CurrentUserProvider
import de.seuhd.campuscoffee.domain.ports.api.AccountingService
import de.seuhd.campuscoffee.domain.ports.api.ActivityService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springdoc.core.annotations.ParameterObject
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import java.util.UUID

/**
 * Admin read controller for the money views over users (JWT, admin only): every user's balance, and a
 * given user's activity feed (their coffees, purchases, and deposits). Kept separate from [UserController]
 * for cohesion; paging is validated through the shared [PageQuery] object (see its KDoc for why the project
 * validates paging via `@Valid` binding rather than a class-level `@Validated`).
 */
@Tag(name = "Admin accounting", description = "Per-user balances and activity (admin only).")
@Controller
@RequestMapping("/users")
class AdminAccountingController(
    private val accountingService: AccountingService,
    private val activityService: ActivityService,
    private val accountingDtoMapper: AccountingDtoMapper,
    private val currentUserProvider: CurrentUserProvider
) {
    /**
     * Returns every user's current count and balance (admin only). Unpaged, like `GET /users`: the admin
     * overview table shows all users at once, and a coffee group is small. Each user's balance is read
     * from the maintained `user_balance` projection in one query, not by replaying their event stream; the
     * per-user count is still read per user. Page it if membership grows large.
     */
    @Operation(summary = "Get every user's current count and balance.")
    @GetMapping("/overview")
    fun overview(): ResponseEntity<List<UserBalanceDto>> {
        val admin = currentUserProvider.currentUser()
        return ResponseEntity.ok(accountingDtoMapper.toBalanceDtos(accountingService.allBalances(admin)))
    }

    /**
     * Returns a given user's landing summary (count, price, balance, kitty balance, whether the latest
     * coffee is cancellable, and the first page of their activity), the admin-by-id analogue of the
     * self-service `GET /summary`. It drives the admin landing for the selected user. The activity keeps the
     * kitty-funded portion of a split purchase (the admin per-user view), matching `GET /{userId}/activity`.
     *
     * @param userId the user whose summary to read
     * @param page   the validated paging window for the embedded first page of activity
     */
    @Operation(summary = "Get a user's landing summary (admin).")
    @GetMapping("/{userId}/summary")
    fun userSummary(
        @PathVariable userId: UUID,
        @Valid @ParameterObject page: PageQuery
    ): ResponseEntity<UserSummaryDto> {
        val admin = currentUserProvider.currentUser()
        return ResponseEntity.ok(
            accountingDtoMapper.toSummaryDto(
                accountingService.userSummary(
                    userId,
                    page.limitOr(SUMMARY_LIMIT),
                    page.offset,
                    admin,
                    includeKittyPortion = true
                )
            )
        )
    }

    /**
     * Returns a page of the given user's activity feed (coffees, purchases, and deposits, newest first).
     *
     * @param userId the user whose activity to read
     * @param page   the validated paging window (limit/offset)
     */
    @Operation(summary = "Get a page of a user's activity.")
    @GetMapping("/{userId}/activity")
    fun userActivity(
        @PathVariable userId: UUID,
        @Valid @ParameterObject page: PageQuery
    ): ResponseEntity<List<ActivityEntryDto>> {
        val admin = currentUserProvider.currentUser()
        return ResponseEntity.ok(
            // the admin-by-id activity exposes the kitty-funded portion of a split expense (the user-serving
            // /api/activity does not, see AccountingService.userActivity)
            accountingDtoMapper.toEntryDtos(
                activityService.userActivity(
                    userId,
                    page.limitOr(DEFAULT_LIMIT),
                    page.offset,
                    admin,
                    includeKittyPortion = true
                )
            )
        )
    }

    private companion object {
        /** The default page size for the admin user-activity read when the caller supplies no limit. */
        private const val DEFAULT_LIMIT = 20

        /** The default first-page size for the admin landing summary's embedded activity (matches self-service). */
        private const val SUMMARY_LIMIT = 10
    }
}
