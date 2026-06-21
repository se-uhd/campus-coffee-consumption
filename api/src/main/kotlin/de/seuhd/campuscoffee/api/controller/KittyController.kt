package de.seuhd.campuscoffee.api.controller

import de.seuhd.campuscoffee.api.dtos.KittyDto
import de.seuhd.campuscoffee.api.mapper.AccountingDtoMapper
import de.seuhd.campuscoffee.api.security.CurrentUserProvider
import de.seuhd.campuscoffee.domain.ports.api.AccountingService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam

/**
 * Admin controller for the kitty ledger (JWT, admin only): the kitty balance and a page of its individual
 * movements. Members see only the kitty balance, which arrives in their landing summary, never these
 * movements (which would reveal who settled and contributed).
 */
@Tag(name = "Kitty", description = "The communal kitty balance and its movements (admin only).")
@Controller
@RequestMapping("/kitty")
class KittyController(
    private val accountingService: AccountingService,
    private val accountingDtoMapper: AccountingDtoMapper,
    private val currentUserProvider: CurrentUserProvider
) {
    /**
     * Returns the kitty balance and a page of its movements (newest first).
     *
     * @param limit  the maximum number of movements to return
     * @param offset the number of newest movements to skip
     */
    @Operation(summary = "Get the kitty balance and a page of its movements.")
    @GetMapping("/ledger")
    fun ledger(
        @Parameter(description = "Maximum number of movements to return.")
        @RequestParam(defaultValue = "50") limit: Int,
        @Parameter(description = "Number of newest movements to skip.")
        @RequestParam(defaultValue = "0") offset: Int
    ): ResponseEntity<KittyDto> {
        val admin = currentUserProvider.currentUser()
        val entries = accountingService.kittyLedger(limit, offset, admin)
        return ResponseEntity.ok(accountingDtoMapper.toKittyDto(accountingService.kittyBalanceCents(), entries))
    }
}
