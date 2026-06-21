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
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam

/**
 * Member self-service read controller (capability token). `GET /summary` returns everything the landing
 * page needs in one call; `GET /ledger` pages through the member's full unified ledger.
 */
@Tag(name = "Summary", description = "A member's landing summary and unified ledger (X-Coffee-Token).")
@Controller
class SummaryController(
    private val accountingService: AccountingService,
    private val accountingDtoMapper: AccountingDtoMapper,
    private val currentUserProvider: CurrentUserProvider
) {
    /**
     * Returns the authenticated member's landing summary (count, price, balance, kitty balance, whether
     * the latest coffee is cancellable, and the first page of their ledger).
     *
     * @param ledgerLimit  the number of ledger entries on the first page
     * @param ledgerOffset the number of newest ledger entries to skip
     */
    @Operation(summary = "Get the authenticated member's landing summary.")
    @GetMapping("/summary")
    fun summary(
        @Parameter(description = "Number of ledger entries on the first page.")
        @RequestParam(defaultValue = "10") ledgerLimit: Int,
        @Parameter(description = "Number of newest ledger entries to skip.")
        @RequestParam(defaultValue = "0") ledgerOffset: Int
    ): ResponseEntity<MemberSummaryDto> {
        val member = currentUserProvider.currentUser()
        return ResponseEntity.ok(
            accountingDtoMapper.toSummaryDto(
                accountingService.memberSummary(member.persistedId, ledgerLimit, ledgerOffset, member)
            )
        )
    }

    /**
     * Returns a page of the authenticated member's unified ledger (newest first).
     *
     * @param limit  the maximum number of entries to return
     * @param offset the number of newest entries to skip
     */
    @Operation(summary = "Get a page of the authenticated member's unified ledger.")
    @GetMapping("/ledger")
    fun ledger(
        @Parameter(description = "Maximum number of entries to return.")
        @RequestParam(defaultValue = "20") limit: Int,
        @Parameter(description = "Number of newest entries to skip.")
        @RequestParam(defaultValue = "0") offset: Int
    ): ResponseEntity<List<LedgerEntryDto>> {
        val member = currentUserProvider.currentUser()
        return ResponseEntity.ok(
            accountingDtoMapper.toEntryDtos(accountingService.memberLedger(member.persistedId, limit, offset, member))
        )
    }
}
