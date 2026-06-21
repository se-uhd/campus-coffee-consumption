package de.seuhd.campuscoffee.api.controller

import de.seuhd.campuscoffee.api.dtos.MemberSummaryDto
import de.seuhd.campuscoffee.api.mapper.AccountingDtoMapper
import de.seuhd.campuscoffee.api.security.CurrentUserProvider
import de.seuhd.campuscoffee.domain.model.User
import de.seuhd.campuscoffee.domain.model.persistedId
import de.seuhd.campuscoffee.domain.ports.api.AccountingService
import de.seuhd.campuscoffee.domain.ports.api.CoffeeConsumptionService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping

/**
 * Member self-service controller for adding and undoing the member's own coffees. The caller is
 * authenticated by their capability token (the `X-Coffee-Token` header), so every operation acts on the
 * token's member. A member adds one coffee at a time (`POST /consumption`) and may undo their most recent
 * coffee within the grace period (`POST /consumption/cancel`); any other adjustment is the admin's job.
 * Each call returns the refreshed member summary (count, price, balance, kitty balance, ledger), so the SPA
 * repaints from one response.
 */
@Tag(
    name = "Consumption",
    description = "Adding and undoing a member's own coffees, authenticated by their capability token (X-Coffee-Token)."
)
@Controller
@RequestMapping("/consumption")
class ConsumptionController(
    private val coffeeConsumptionService: CoffeeConsumptionService,
    private val accountingService: AccountingService,
    private val accountingDtoMapper: AccountingDtoMapper,
    private val currentUserProvider: CurrentUserProvider
) {
    /** Adds one coffee to the authenticated member's count and returns the refreshed summary. */
    @Operation(summary = "Add one coffee to the authenticated member's count.")
    @PostMapping("")
    fun add(): ResponseEntity<MemberSummaryDto> {
        val member = currentUserProvider.currentUser()
        coffeeConsumptionService.applyDelta(member.persistedId, 1, member)
        return ResponseEntity.ok(summary(member))
    }

    /**
     * Undoes the authenticated member's most recent coffee if it is still within the grace period, and
     * returns the refreshed summary. Nothing to undo or the grace period passed yields 409.
     */
    @Operation(summary = "Undo the authenticated member's most recent coffee (within the grace period).")
    @PostMapping("/cancel")
    fun cancel(): ResponseEntity<MemberSummaryDto> {
        val member = currentUserProvider.currentUser()
        coffeeConsumptionService.cancel(member.persistedId, member)
        return ResponseEntity.ok(summary(member))
    }

    /** Builds the refreshed member summary (with the first page of the ledger) for [member]. */
    private fun summary(member: User): MemberSummaryDto =
        accountingDtoMapper.toSummaryDto(
            accountingService.memberSummary(member.persistedId, DEFAULT_LEDGER_LIMIT, 0, member)
        )

    private companion object {
        private const val DEFAULT_LEDGER_LIMIT = 10
    }
}
