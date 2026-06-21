package de.seuhd.campuscoffee.api.controller

import de.seuhd.campuscoffee.api.dtos.MemberExpenseDto
import de.seuhd.campuscoffee.api.dtos.MemberSummaryDto
import de.seuhd.campuscoffee.api.mapper.AccountingDtoMapper
import de.seuhd.campuscoffee.api.security.CurrentUserProvider
import de.seuhd.campuscoffee.domain.model.persistedId
import de.seuhd.campuscoffee.domain.ports.api.AccountingService
import de.seuhd.campuscoffee.domain.ports.api.ExpenseService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping

/**
 * Member self-service controller for recording the member's own bean purchases (capability token). The
 * purchase is always booked 100% from the member's own pocket — the server attributes it to the calling
 * member, never to anyone the request names — and the response is the refreshed member summary. Correcting
 * or deleting a purchase is admin-only (the SPA states this to the member).
 */
@Tag(name = "Expenses", description = "A member recording their own bean purchases (X-Coffee-Token).")
@Controller
@RequestMapping("/expenses")
class ExpenseController(
    private val expenseService: ExpenseService,
    private val accountingService: AccountingService,
    private val accountingDtoMapper: AccountingDtoMapper,
    private val currentUserProvider: CurrentUserProvider
) {
    /**
     * Records the authenticated member's own bean purchase and returns the refreshed summary.
     *
     * @param dto the purchase (weight, amount, optional note)
     */
    @Operation(summary = "Record the authenticated member's own bean purchase.")
    @PostMapping("")
    fun record(
        @RequestBody @Valid dto: MemberExpenseDto
    ): ResponseEntity<MemberSummaryDto> {
        val member = currentUserProvider.currentUser()
        expenseService.recordOwn(
            weightGrams = requireNotNull(dto.weightGrams),
            amountCents = requireNotNull(dto.amountCents),
            note = dto.note,
            actingUser = member
        )
        return ResponseEntity.ok(
            accountingDtoMapper.toSummaryDto(
                accountingService.memberSummary(member.persistedId, DEFAULT_LEDGER_LIMIT, 0, member)
            )
        )
    }

    private companion object {
        private const val DEFAULT_LEDGER_LIMIT = 10
    }
}
