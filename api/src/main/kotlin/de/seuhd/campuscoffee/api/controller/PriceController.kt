package de.seuhd.campuscoffee.api.controller

import de.seuhd.campuscoffee.api.dtos.PriceChangeDto
import de.seuhd.campuscoffee.api.dtos.PriceDto
import de.seuhd.campuscoffee.api.dtos.PriceUpdateDto
import de.seuhd.campuscoffee.api.mapper.AccountingDtoMapper
import de.seuhd.campuscoffee.api.security.CurrentUserProvider
import de.seuhd.campuscoffee.domain.ports.api.CoffeePriceService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springdoc.core.annotations.ParameterObject
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping

/**
 * Admin controller for the global coffee price (JWT, admin only): set the price, read the current price
 * (`GET /api/price`, consumed by the admin landing), and read its history. Members do not call this
 * resource; the current price reaches them through their landing summary (`GET /api/summary`).
 */
@Tag(name = "Price", description = "Setting and inspecting the global coffee price (admin only).")
@Controller
@RequestMapping("/price")
class PriceController(
    private val coffeePriceService: CoffeePriceService,
    private val accountingDtoMapper: AccountingDtoMapper,
    private val currentUserProvider: CurrentUserProvider
) {
    /**
     * Sets the global price per cup and returns it.
     *
     * @param dto the new price (euro cents)
     */
    @Operation(summary = "Set the global coffee price per cup.")
    @PutMapping("")
    fun setPrice(
        @RequestBody @Valid dto: PriceUpdateDto
    ): ResponseEntity<PriceDto> {
        val admin = currentUserProvider.currentUser()
        val price = coffeePriceService.setPrice(requireNotNull(dto.amountCents), admin)
        return ResponseEntity.ok(PriceDto(price.amountCents))
    }

    /** Returns the current global price per cup. */
    @Operation(summary = "Get the current global coffee price per cup.")
    @GetMapping("")
    fun getCurrent(): ResponseEntity<PriceDto> {
        // re-resolve the live admin so a deactivated or demoted admin's in-flight JWT is rejected here too,
        // matching every other admin endpoint (the static roles claim alone would still let it through)
        currentUserProvider.currentUser()
        return ResponseEntity.ok(PriceDto(coffeePriceService.getCurrent().amountCents))
    }

    /**
     * Returns a page of the global price history, newest first.
     *
     * @param page the validated paging window (limit/offset)
     */
    @Operation(summary = "Get a page of the global coffee price history (newest first).")
    @GetMapping("/history")
    fun history(
        @Valid @ParameterObject page: PageQuery
    ): ResponseEntity<List<PriceChangeDto>> {
        val admin = currentUserProvider.currentUser()
        // the price stream is small; page it in memory (the service already returns it newest-first) so the
        // response is bounded, consistent with the other history reads
        val history =
            coffeePriceService
                .priceHistory(admin)
                .drop(page.offset)
                .take(page.limitOr(HISTORY_LIMIT))
        return ResponseEntity.ok(accountingDtoMapper.toPriceChangeDtos(history))
    }

    private companion object {
        private const val HISTORY_LIMIT = 50
    }
}
