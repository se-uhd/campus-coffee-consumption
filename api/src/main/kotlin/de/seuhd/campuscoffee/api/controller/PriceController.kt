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
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping

/**
 * Admin controller for the global coffee price (JWT, admin only): set the price and read its history. The
 * current price reaches members through their landing summary, and the newest history entry is the current
 * price, so there is no separate member price read here.
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

    /** Returns the global price history, newest first. */
    @Operation(summary = "Get the global coffee price history (newest first).")
    @GetMapping("/history")
    fun history(): ResponseEntity<List<PriceChangeDto>> {
        val admin = currentUserProvider.currentUser()
        return ResponseEntity.ok(accountingDtoMapper.toPriceChangeDtos(coffeePriceService.priceHistory(admin)))
    }
}
