package de.seuhd.campuscoffee.api.controller

import de.seuhd.campuscoffee.api.dtos.ActivityEntryDto
import de.seuhd.campuscoffee.api.dtos.UserBalanceDto
import de.seuhd.campuscoffee.api.mapper.AccountingDtoMapper
import de.seuhd.campuscoffee.api.security.CurrentUserProvider
import de.seuhd.campuscoffee.domain.ports.api.AccountingService
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
 * Admin read controller for the money views over members (JWT, admin only): every member's balance, and a
 * given member's activity feed (their coffees, purchases, and deposits). Kept separate from [UserController]
 * for cohesion; paging is validated through the shared [PageQuery] object (see its KDoc for why the project
 * validates paging via `@Valid` binding rather than a class-level `@Validated`).
 */
@Tag(name = "Admin accounting", description = "Per-member balances and activity (admin only).")
@Controller
@RequestMapping("/users")
class AdminAccountingController(
    private val accountingService: AccountingService,
    private val accountingDtoMapper: AccountingDtoMapper,
    private val currentUserProvider: CurrentUserProvider
) {
    /**
     * Returns every member's current count and balance (admin only). Deliberately unpaged, like
     * `GET /users`: the admin overview table shows all members at once, and a coffee group is small. Each
     * member's balance is a walk over their event stream, so this is O(members); page it if membership
     * grows large.
     */
    @Operation(summary = "Get every member's current count and balance.")
    @GetMapping("/overview")
    fun overview(): ResponseEntity<List<UserBalanceDto>> {
        val admin = currentUserProvider.currentUser()
        return ResponseEntity.ok(accountingDtoMapper.toBalanceDtos(accountingService.allBalances(admin)))
    }

    /**
     * Returns a page of the given member's activity feed (coffees, purchases, and deposits, newest first).
     *
     * @param userId the member whose activity to read
     * @param page   the validated paging window (limit/offset)
     */
    @Operation(summary = "Get a page of a member's activity.")
    @GetMapping("/{userId}/activity")
    fun userActivity(
        @PathVariable userId: UUID,
        @Valid @ParameterObject page: PageQuery
    ): ResponseEntity<List<ActivityEntryDto>> {
        val admin = currentUserProvider.currentUser()
        return ResponseEntity.ok(
            // the admin-by-id activity exposes the kitty-funded portion of a split expense (the member-serving
            // /api/activity does not, see AccountingService.userActivity)
            accountingDtoMapper.toEntryDtos(
                accountingService.userActivity(
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
        /** The default page size for the admin member-activity read when the caller supplies no limit. */
        private const val DEFAULT_LIMIT = 20
    }
}
