package de.seuhd.campuscoffee.api.controller

import de.seuhd.campuscoffee.api.dtos.LedgerEntryDto
import de.seuhd.campuscoffee.api.dtos.MemberSummaryDto
import de.seuhd.campuscoffee.api.mapper.AccountingDtoMapper
import de.seuhd.campuscoffee.api.security.CurrentUserProvider
import de.seuhd.campuscoffee.domain.model.persistedId
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
import org.springframework.web.bind.annotation.RequestParam

/**
 * The authenticated member's own self-service reads (capability token). `GET /summary` returns everything
 * the landing page needs in one call; `GET /activity` pages through the member's full activity feed (their
 * unified ledger of coffees, purchases, and settlements, newest first).
 */
@Tag(name = "Member", description = "The authenticated member's own landing summary and activity (X-Coffee-Token).")
@Validated
@Controller
class MemberController(
    private val accountingService: AccountingService,
    private val accountingDtoMapper: AccountingDtoMapper,
    private val currentUserProvider: CurrentUserProvider
) {
    /**
     * Returns the authenticated member's landing summary (count, price, balance, kitty balance, whether
     * the latest coffee is cancellable, and the first page of their activity).
     *
     * @param ledgerLimit  the number of activity entries on the first page
     * @param ledgerOffset the number of newest activity entries to skip
     */
    @Operation(summary = "Get the authenticated member's landing summary.")
    @GetMapping("/summary")
    fun summary(
        @Parameter(description = "Number of activity entries on the first page.")
        @RequestParam(defaultValue = "10")
        @Positive
        @Max(MAX_PAGE_LIMIT) ledgerLimit: Int,
        @Parameter(description = "Number of newest activity entries to skip.")
        @RequestParam(defaultValue = "0")
        @Min(0) ledgerOffset: Int
    ): ResponseEntity<MemberSummaryDto> {
        val member = currentUserProvider.currentUser()
        return ResponseEntity.ok(
            accountingDtoMapper.toSummaryDto(
                accountingService.memberSummary(member.persistedId, ledgerLimit, ledgerOffset, member)
            )
        )
    }

    /**
     * Returns a page of the authenticated member's activity feed (their unified ledger, newest first).
     *
     * @param limit  the maximum number of entries to return
     * @param offset the number of newest entries to skip
     */
    @Operation(summary = "Get a page of the authenticated member's activity.")
    @GetMapping("/activity")
    fun activity(
        @Parameter(description = "Maximum number of entries to return.")
        @RequestParam(defaultValue = "20")
        @Positive
        @Max(MAX_PAGE_LIMIT) limit: Int,
        @Parameter(description = "Number of newest entries to skip.")
        @RequestParam(defaultValue = "0")
        @Min(0) offset: Int
    ): ResponseEntity<List<LedgerEntryDto>> {
        val member = currentUserProvider.currentUser()
        return ResponseEntity.ok(
            accountingDtoMapper.toEntryDtos(accountingService.memberLedger(member.persistedId, limit, offset, member))
        )
    }

    private companion object {
        /** The maximum page size a paged read accepts; an out-of-range value is a 400, not a silent clamp. */
        private const val MAX_PAGE_LIMIT = 100L
    }
}
