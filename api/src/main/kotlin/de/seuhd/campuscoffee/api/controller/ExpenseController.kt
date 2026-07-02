package de.seuhd.campuscoffee.api.controller

import de.seuhd.campuscoffee.api.dtos.OwnExpenseDto
import de.seuhd.campuscoffee.api.dtos.UserSummaryDto
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
 * User self-service controller for recording the user's own bean purchases (capability token). The
 * purchase is always booked 100% from the user's own pocket (the server attributes it to the calling
 * user, never to anyone the request names), and the response is the refreshed user summary. Correcting
 * or deleting a purchase is admin-only (the SPA states this to the user).
 */
@Tag(name = "Expenses", description = "A user recording their own bean purchases (X-Capability-Token).")
@Controller
@RequestMapping("/expenses")
class ExpenseController(
    private val expenseService: ExpenseService,
    private val accountingService: AccountingService,
    private val accountingDtoMapper: AccountingDtoMapper,
    private val currentUserProvider: CurrentUserProvider
) {
    /**
     * Records the authenticated user's own bean purchase and returns the refreshed summary.
     *
     * @param dto the purchase (weight, amount, optional note)
     */
    @Operation(summary = "Record the authenticated user's own bean purchase.")
    @PostMapping("")
    fun record(
        @RequestBody @Valid dto: OwnExpenseDto
    ): ResponseEntity<UserSummaryDto> {
        val user = currentUserProvider.currentUser()
        expenseService.recordOwn(
            expenseType = requireNotNull(dto.expenseType),
            beanName = dto.beanName,
            weightGrams = dto.weightGrams,
            amountCents = requireNotNull(dto.amountCents),
            note = dto.note,
            actingUser = user
        )
        return ResponseEntity.ok(
            accountingDtoMapper.toSummaryDto(
                accountingService.userSummary(user.persistedId, DEFAULT_ACTIVITY_LIMIT, 0, user)
            )
        )
    }

    private companion object {
        private const val DEFAULT_ACTIVITY_LIMIT = 10
    }
}
