package de.seuhd.campuscoffee.api.controller

import de.seuhd.campuscoffee.api.dtos.ConsumptionDeltaDto
import de.seuhd.campuscoffee.api.dtos.ConsumptionDto
import de.seuhd.campuscoffee.api.dtos.ConsumptionOverrideDto
import de.seuhd.campuscoffee.api.mapper.ConsumptionDtoMapper
import de.seuhd.campuscoffee.api.security.CurrentUserProvider
import de.seuhd.campuscoffee.domain.model.User
import de.seuhd.campuscoffee.domain.ports.api.CoffeeConsumptionService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springdoc.core.annotations.ParameterObject
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import java.util.UUID

/**
 * Admin controller for viewing and adjusting any user's coffee consumption by user id (the admin
 * landing and count corrections). The resource is admin-only (gated in the security configuration); the
 * absolute override ([overrideTotal]) is the admin-only correction a regular user cannot perform on
 * their own count. Every view and mutation returns the composite [ConsumptionDto] (current total plus a
 * page of recent changes).
 */
@Tag(name = "User consumption (admin)", description = "Admin view and adjustment of any user's coffee consumption.")
@Controller
@RequestMapping("/users/{userId}/consumption")
class AdminConsumptionController(
    private val coffeeConsumptionService: CoffeeConsumptionService,
    private val consumptionDtoMapper: ConsumptionDtoMapper,
    private val currentUserProvider: CurrentUserProvider
) {
    /**
     * Returns the current total and a page of recent changes (newest first) of the user with [userId].
     *
     * @param userId the id of the user whose consumption to read
     * @param page   the validated paging window (limit/offset)
     */
    @Operation(summary = "Get a user's current coffee total and recent changes.")
    @GetMapping("")
    fun get(
        @Parameter(description = "Unique identifier of the user.", required = true)
        @PathVariable userId: UUID,
        @Valid @ParameterObject page: PageQuery
    ): ResponseEntity<ConsumptionDto> = ResponseEntity.ok(response(userId, page.limitOr(DEFAULT_LIMIT), page.offset))

    /**
     * Applies a single-step change (`+1` or `-1`) to the count of the user with [userId].
     *
     * @param userId the id of the user whose count to change
     * @param dto    the single-step delta to apply (exactly `+1` or `-1`)
     */
    @Operation(summary = "Apply a +1/-1 change to a user's coffee count.")
    @PostMapping("")
    fun change(
        @Parameter(description = "Unique identifier of the user.", required = true)
        @PathVariable userId: UUID,
        @Parameter(description = "The single-step delta to apply (+1 or -1).", required = true)
        @RequestBody
        @Valid dto: ConsumptionDeltaDto
    ): ResponseEntity<ConsumptionDto> {
        val actingUser = currentUserProvider.currentUser()
        val updated = coffeeConsumptionService.applyDelta(userId, requireNotNull(dto.delta), actingUser)
        return ResponseEntity.ok(consumptionDtoMapper.toDto(updated.count, recentChanges(userId, actingUser)))
    }

    /**
     * Overrides the count of the user with [userId] to an explicit value (an admin correction; any
     * non-negative total). An optional note documents the reason.
     *
     * @param userId the id of the user whose count to set
     * @param dto    the new, non-negative total and an optional note
     */
    @Operation(summary = "Override a user's coffee count to an explicit value (admin correction).")
    @PutMapping("")
    fun overrideTotal(
        @Parameter(description = "Unique identifier of the user.", required = true)
        @PathVariable userId: UUID,
        @Parameter(description = "The new, non-negative total and an optional note.", required = true)
        @RequestBody
        @Valid dto: ConsumptionOverrideDto
    ): ResponseEntity<ConsumptionDto> {
        val actingUser = currentUserProvider.currentUser()
        val updated = coffeeConsumptionService.setTotal(userId, requireNotNull(dto.total), dto.note, actingUser)
        return ResponseEntity.ok(consumptionDtoMapper.toDto(updated.count, recentChanges(userId, actingUser)))
    }

    /** Builds the composite response (current total + a page of recent changes) for the user. */
    private fun response(
        userId: UUID,
        limit: Int,
        offset: Int
    ): ConsumptionDto {
        val actingUser = currentUserProvider.currentUser()
        val consumption = coffeeConsumptionService.getByUserId(userId, actingUser)
        val changes = coffeeConsumptionService.recentChanges(userId, limit, offset, actingUser)
        return consumptionDtoMapper.toDto(consumption.count, changes)
    }

    /** The default first page of changes returned alongside a mutation. */
    private fun recentChanges(
        userId: UUID,
        actingUser: User
    ) = coffeeConsumptionService.recentChanges(userId, DEFAULT_LIMIT, 0, actingUser)

    private companion object {
        /** The default page size for the change-log read when the caller supplies no limit. */
        private const val DEFAULT_LIMIT = 5
    }
}
