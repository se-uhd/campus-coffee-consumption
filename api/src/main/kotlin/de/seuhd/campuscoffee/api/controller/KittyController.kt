package de.seuhd.campuscoffee.api.controller

import de.seuhd.campuscoffee.api.dtos.AdjustmentRequestDto
import de.seuhd.campuscoffee.api.dtos.KittyDto
import de.seuhd.campuscoffee.api.dtos.PaymentDto
import de.seuhd.campuscoffee.api.dtos.SettlementRequestDto
import de.seuhd.campuscoffee.api.mapper.AccountingDtoMapper
import de.seuhd.campuscoffee.api.mapper.PaymentDtoMapper
import de.seuhd.campuscoffee.api.security.CurrentUserProvider
import de.seuhd.campuscoffee.domain.ports.api.AccountingService
import de.seuhd.campuscoffee.domain.ports.api.PaymentService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Positive
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.bind.annotation.ResponseStatus

/**
 * Admin controller for the communal kitty (JWT, admin only): its balance and history, and the two money
 * movements made from the kitty page, a member **deposit** (a member paid money in, which credits them and
 * feeds the kitty) and a kitty **adjustment** (an initial float or a correction, which may be negative).
 * Members see only the kitty balance, which arrives in their landing summary, never the history (which
 * would reveal who deposited and contributed).
 */
@Tag(name = "Kitty", description = "The communal kitty: balance, history, deposits, and adjustments (admin only).")
@Validated
@Controller
@RequestMapping("/kitty")
class KittyController(
    private val accountingService: AccountingService,
    private val accountingDtoMapper: AccountingDtoMapper,
    private val paymentService: PaymentService,
    private val paymentDtoMapper: PaymentDtoMapper,
    private val currentUserProvider: CurrentUserProvider
) {
    /**
     * Returns the kitty balance and a page of its history (newest first).
     *
     * @param limit  the maximum number of history entries to return
     * @param offset the number of newest entries to skip
     */
    @Operation(summary = "Get the kitty balance and a page of its history.")
    @GetMapping("/history")
    fun history(
        @Parameter(description = "Maximum number of history entries to return.")
        @RequestParam(defaultValue = "50")
        @Positive
        @Max(MAX_PAGE_LIMIT) limit: Int,
        @Parameter(description = "Number of newest entries to skip.")
        @RequestParam(defaultValue = "0")
        @Min(0) offset: Int
    ): ResponseEntity<KittyDto> {
        val admin = currentUserProvider.currentUser()
        val entries = accountingService.kittyLedger(limit, offset, admin)
        return ResponseEntity.ok(accountingDtoMapper.toKittyDto(accountingService.kittyBalanceCents(), entries))
    }

    /**
     * Records that a member paid money into the kitty (a deposit, which credits the member and feeds the kitty).
     *
     * @param dto the deposit (member, positive amount, optional note)
     */
    @Operation(summary = "Record a member deposit (money paid into the kitty).")
    @ResponseStatus(HttpStatus.CREATED)
    @ResponseBody
    @PostMapping("/deposit")
    fun deposit(
        @RequestBody @Valid dto: SettlementRequestDto
    ): PaymentDto {
        val admin = currentUserProvider.currentUser()
        val payment =
            paymentService.recordSettlement(
                requireNotNull(dto.userId),
                requireNotNull(dto.amountCents),
                dto.note,
                admin
            )
        return paymentDtoMapper.toDto(payment)
    }

    /**
     * Adjusts the kitty without a member (an initial float, or a correction; may be negative).
     *
     * @param dto the adjustment (signed amount, optional note)
     */
    @Operation(summary = "Adjust the kitty (initial float or correction).")
    @ResponseStatus(HttpStatus.CREATED)
    @ResponseBody
    @PostMapping("/adjustment")
    fun adjustment(
        @RequestBody @Valid dto: AdjustmentRequestDto
    ): PaymentDto {
        val admin = currentUserProvider.currentUser()
        val payment = paymentService.adjustKitty(requireNotNull(dto.amountCents), dto.note, admin)
        return paymentDtoMapper.toDto(payment)
    }

    private companion object {
        /** The maximum page size a paged read accepts; an out-of-range value is a 400, not a silent clamp. */
        private const val MAX_PAGE_LIMIT = 100L
    }
}
