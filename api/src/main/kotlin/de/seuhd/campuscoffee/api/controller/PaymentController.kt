package de.seuhd.campuscoffee.api.controller

import de.seuhd.campuscoffee.api.dtos.AdjustmentRequestDto
import de.seuhd.campuscoffee.api.dtos.PaymentDto
import de.seuhd.campuscoffee.api.dtos.SettlementRequestDto
import de.seuhd.campuscoffee.api.mapper.PaymentDtoMapper
import de.seuhd.campuscoffee.api.security.CurrentUserProvider
import de.seuhd.campuscoffee.domain.ports.api.PaymentService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.bind.annotation.ResponseStatus

/**
 * Admin controller for kitty money movements (JWT, admin only): record a member's settlement (money in,
 * crediting them and feeding the kitty) or adjust the kitty directly (an initial float, or a correction).
 */
@Tag(name = "Payments", description = "Recording member settlements and kitty adjustments (admin only).")
@Controller
@RequestMapping("/payments")
class PaymentController(
    private val paymentService: PaymentService,
    private val paymentDtoMapper: PaymentDtoMapper,
    private val currentUserProvider: CurrentUserProvider
) {
    /**
     * Records that a member paid money into the kitty.
     *
     * @param dto the settlement (member, positive amount, optional note)
     */
    @Operation(summary = "Record a member's settlement (money paid into the kitty).")
    @ResponseStatus(HttpStatus.CREATED)
    @ResponseBody
    @PostMapping("/settlement")
    fun settle(
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
    fun adjust(
        @RequestBody @Valid dto: AdjustmentRequestDto
    ): PaymentDto {
        val admin = currentUserProvider.currentUser()
        val payment = paymentService.adjustKitty(requireNotNull(dto.amountCents), dto.note, admin)
        return paymentDtoMapper.toDto(payment)
    }
}
