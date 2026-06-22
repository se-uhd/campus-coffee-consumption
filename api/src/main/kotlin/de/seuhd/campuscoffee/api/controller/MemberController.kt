package de.seuhd.campuscoffee.api.controller

import de.seuhd.campuscoffee.api.dtos.LedgerEntryDto
import de.seuhd.campuscoffee.api.dtos.MemberSummaryDto
import de.seuhd.campuscoffee.api.mapper.AccountingDtoMapper
import de.seuhd.campuscoffee.api.security.CurrentUserProvider
import de.seuhd.campuscoffee.domain.model.persistedId
import de.seuhd.campuscoffee.domain.ports.api.AccountingService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springdoc.core.annotations.ParameterObject
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping

/**
 * The authenticated member's own self-service reads (capability token). `GET /summary` returns everything
 * the landing page needs in one call; `GET /activity` pages through the member's full activity feed (their
 * unified ledger of coffees, purchases, and settlements, newest first). Both page through the shared
 * [PageQuery] (`limit`/`offset`), validated via `@Valid` binding rather than a class-level `@Validated`.
 */
@Tag(name = "Member", description = "The authenticated member's own landing summary and activity (X-Coffee-Token).")
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
     * @param page the validated paging window for the embedded first page of activity
     */
    @Operation(summary = "Get the authenticated member's landing summary.")
    @GetMapping("/summary")
    fun summary(
        @Valid @ParameterObject page: PageQuery
    ): ResponseEntity<MemberSummaryDto> {
        val member = currentUserProvider.currentUser()
        return ResponseEntity.ok(
            accountingDtoMapper.toSummaryDto(
                accountingService.memberSummary(member.persistedId, page.limitOr(SUMMARY_LIMIT), page.offset, member)
            )
        )
    }

    /**
     * Returns a page of the authenticated member's activity feed (their unified ledger, newest first).
     *
     * @param page the validated paging window (limit/offset)
     */
    @Operation(summary = "Get a page of the authenticated member's activity.")
    @GetMapping("/activity")
    fun activity(
        @Valid @ParameterObject page: PageQuery
    ): ResponseEntity<List<LedgerEntryDto>> {
        val member = currentUserProvider.currentUser()
        return ResponseEntity.ok(
            accountingDtoMapper.toEntryDtos(
                accountingService.memberLedger(member.persistedId, page.limitOr(ACTIVITY_LIMIT), page.offset, member)
            )
        )
    }

    private companion object {
        /** The default first-page size for the landing summary's embedded activity. */
        private const val SUMMARY_LIMIT = 10

        /** The default page size for the standalone activity feed. */
        private const val ACTIVITY_LIMIT = 20
    }
}
