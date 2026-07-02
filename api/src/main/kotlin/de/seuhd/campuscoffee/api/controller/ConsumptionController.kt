package de.seuhd.campuscoffee.api.controller

import de.seuhd.campuscoffee.api.dtos.RatingRequestDto
import de.seuhd.campuscoffee.api.dtos.UserSummaryDto
import de.seuhd.campuscoffee.api.mapper.AccountingDtoMapper
import de.seuhd.campuscoffee.api.security.CurrentUserProvider
import de.seuhd.campuscoffee.domain.model.User
import de.seuhd.campuscoffee.domain.model.persistedId
import de.seuhd.campuscoffee.domain.ports.api.AccountingService
import de.seuhd.campuscoffee.domain.ports.api.CoffeeConsumptionService
import de.seuhd.campuscoffee.domain.ports.api.CoffeeRatingService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping

/**
 * User self-service controller for adding and undoing the user's own coffees. The caller is
 * authenticated by their capability token (the `X-Capability-Token` header), so every operation acts on the
 * token's user. A user adds one coffee at a time (`POST /consumption`) and may undo their most recent
 * coffee within the grace period (`POST /consumption/cancel`); any other adjustment is the admin's job.
 * Each call returns the refreshed user summary (count, price, balance, kitty balance, activity), so the SPA
 * repaints from one response.
 */
@Tag(
    name = "Consumption",
    description = "Adding and undoing a user's own coffees (X-Capability-Token)."
)
@Controller
@RequestMapping("/consumption")
class ConsumptionController(
    private val coffeeConsumptionService: CoffeeConsumptionService,
    private val coffeeRatingService: CoffeeRatingService,
    private val accountingService: AccountingService,
    private val accountingDtoMapper: AccountingDtoMapper,
    private val currentUserProvider: CurrentUserProvider
) {
    /** Adds one coffee to the authenticated user's count and returns the refreshed summary. */
    @Operation(summary = "Add one coffee to the authenticated user's count.")
    @PostMapping("")
    fun add(): ResponseEntity<UserSummaryDto> {
        val user = currentUserProvider.currentUser()
        coffeeConsumptionService.applyDelta(user.persistedId, 1, user)
        return ResponseEntity.ok(summary(user))
    }

    /**
     * Undoes the authenticated user's most recent coffee if it is still within the grace period, and
     * returns the refreshed summary. Nothing to undo or the grace period passed yields 409.
     */
    @Operation(summary = "Undo the authenticated user's most recent coffee (within the grace period).")
    @PostMapping("/cancel")
    fun cancel(): ResponseEntity<UserSummaryDto> {
        val user = currentUserProvider.currentUser()
        coffeeConsumptionService.cancel(user.persistedId, user)
        return ResponseEntity.ok(summary(user))
    }

    /**
     * Sets or updates the authenticated user's rating for the current cup window on the given bean, and
     * returns the refreshed summary. Allowed only while the user has a cancellable cup within grace (else
     * 409); a repeat call within the same window updates the one vote.
     *
     * @param dto the bean to rate and the value (one to five)
     */
    @Operation(summary = "Rate the beans of the authenticated user's current coffee (one to five).")
    @PutMapping("/rating")
    fun rate(
        @RequestBody @Valid dto: RatingRequestDto
    ): ResponseEntity<UserSummaryDto> {
        val user = currentUserProvider.currentUser()
        coffeeRatingService.rateCurrentBean(
            user.persistedId,
            requireNotNull(dto.beanId),
            requireNotNull(dto.value),
            user
        )
        return ResponseEntity.ok(summary(user))
    }

    /** Builds the refreshed summary (with the first page of the activity) for [user]. */
    private fun summary(user: User): UserSummaryDto =
        accountingDtoMapper.toSummaryDto(
            accountingService.userSummary(user.persistedId, DEFAULT_ACTIVITY_LIMIT, 0, user)
        )

    private companion object {
        private const val DEFAULT_ACTIVITY_LIMIT = 10
    }
}
