package de.seuhd.campuscoffee.api.controller

import de.seuhd.campuscoffee.api.dtos.LedgerEntryDto
import de.seuhd.campuscoffee.api.dtos.MemberBalanceDto
import de.seuhd.campuscoffee.api.mapper.AccountingDtoMapper
import de.seuhd.campuscoffee.api.security.CurrentUserProvider
import de.seuhd.campuscoffee.domain.ports.api.AccountingService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Positive
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import java.util.UUID

/**
 * Admin read controller for the money views over members (JWT, admin only): every member's balance, and a
 * given member's unified ledger.
 */
@Tag(name = "Admin accounting", description = "Per-member balances and ledgers (admin only).")
@Validated
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
    fun overview(): ResponseEntity<List<MemberBalanceDto>> {
        val admin = currentUserProvider.currentUser()
        return ResponseEntity.ok(accountingDtoMapper.toBalanceDtos(accountingService.allBalances(admin)))
    }

    /**
     * Returns a page of the given member's activity feed (their unified ledger, newest first).
     *
     * @param userId the member whose activity to read
     * @param limit  the maximum number of entries to return
     * @param offset the number of newest entries to skip
     */
    @Operation(summary = "Get a page of a member's activity.")
    @GetMapping("/{userId}/activity")
    fun memberActivity(
        @PathVariable userId: UUID,
        @Parameter(description = "Maximum number of entries to return.")
        @RequestParam(defaultValue = "20")
        @Positive
        @Max(MAX_PAGE_LIMIT) limit: Int,
        @Parameter(description = "Number of newest entries to skip.")
        @RequestParam(defaultValue = "0")
        @Min(0) offset: Int
    ): ResponseEntity<List<LedgerEntryDto>> {
        val admin = currentUserProvider.currentUser()
        return ResponseEntity.ok(
            // the admin-by-id activity exposes the kitty-funded portion of a split expense (the member-serving
            // /api/activity does not, see AccountingService.memberLedger)
            accountingDtoMapper.toEntryDtos(
                accountingService.memberLedger(userId, limit, offset, admin, includeKittyPortion = true)
            )
        )
    }

    private companion object {
        /** The maximum page size a paged read accepts; an out-of-range value is a 400, not a silent clamp. */
        private const val MAX_PAGE_LIMIT = 100L
    }
}
