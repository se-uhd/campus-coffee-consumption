package de.seuhd.campuscoffee.api.controller

import de.seuhd.campuscoffee.api.dtos.ConsumptionDeltaDto
import de.seuhd.campuscoffee.api.dtos.ConsumptionDto
import de.seuhd.campuscoffee.api.mapper.ConsumptionDtoMapper
import de.seuhd.campuscoffee.api.security.CurrentUserProvider
import de.seuhd.campuscoffee.domain.model.User
import de.seuhd.campuscoffee.domain.model.persistedId
import de.seuhd.campuscoffee.domain.ports.api.CoffeeConsumptionService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam

/**
 * Member self-service controller for a member's own coffee consumption. The caller is authenticated by
 * their capability token (the `X-Coffee-Token` header), so every operation acts on the token's member —
 * the id never appears in the path. The consumption resource is the change log; each response carries the
 * current total plus a page of recent changes, so the SPA paints the whole view from one request and a
 * mutation needs no follow-up read. The member can only step the count by one (`+1`/`-1`); any other
 * adjustment is the admin's absolute override.
 */
@Tag(
    name = "Consumption",
    description = "A member's own coffee consumption, authenticated by their capability token (X-Coffee-Token)."
)
@Controller
@RequestMapping("/consumption")
class ConsumptionController(
    private val coffeeConsumptionService: CoffeeConsumptionService,
    private val consumptionDtoMapper: ConsumptionDtoMapper,
    private val currentUserProvider: CurrentUserProvider
) {
    /**
     * Returns the authenticated member's current total and a page of their recent changes (newest first).
     *
     * @param limit  the maximum number of changes to return
     * @param offset the number of changes to skip from the newest (for paging)
     */
    @Operation(summary = "Get the authenticated member's current coffee total and recent changes.")
    @GetMapping("")
    fun get(
        @Parameter(description = "Maximum number of changes to return.")
        @RequestParam(defaultValue = DEFAULT_LIMIT) limit: Int,
        @Parameter(description = "Number of changes to skip from the newest (for paging).")
        @RequestParam(defaultValue = "0") offset: Int
    ): ResponseEntity<ConsumptionDto> {
        val member = currentUserProvider.currentUser()
        return ResponseEntity.ok(response(member, limit, offset))
    }

    /**
     * Applies a single-step change (`+1` or `-1`) to the authenticated member's count and returns the new
     * total with the refreshed recent changes. A `-1` at zero yields 409 (counts never go negative).
     *
     * @param dto the single-step delta to apply (exactly `+1` or `-1`)
     */
    @Operation(summary = "Apply a +1/-1 change to the authenticated member's coffee count.")
    @PostMapping("")
    fun change(
        @Parameter(description = "The single-step delta to apply (+1 or -1).", required = true)
        @RequestBody
        @Valid dto: ConsumptionDeltaDto
    ): ResponseEntity<ConsumptionDto> {
        val member = currentUserProvider.currentUser()
        val updated = coffeeConsumptionService.applyDelta(member.persistedId, requireNotNull(dto.delta), member)
        return ResponseEntity.ok(consumptionDtoMapper.toDto(updated.count, recentChanges(member, member)))
    }

    /** Builds the composite response (current total + a page of recent changes) for [member]. */
    private fun response(
        member: User,
        limit: Int,
        offset: Int
    ): ConsumptionDto {
        val consumption = coffeeConsumptionService.getByUserId(member.persistedId, member)
        val changes = coffeeConsumptionService.recentChanges(member.persistedId, limit, offset, member)
        return consumptionDtoMapper.toDto(consumption.count, changes)
    }

    /** The default first page of changes returned alongside a mutation. */
    private fun recentChanges(
        member: User,
        actingUser: User
    ) = coffeeConsumptionService.recentChanges(member.persistedId, DEFAULT_LIMIT.toInt(), 0, actingUser)

    private companion object {
        private const val DEFAULT_LIMIT = "5"
    }
}
