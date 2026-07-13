package de.seuhd.campuscoffee.api.controller

import de.seuhd.campuscoffee.api.dtos.DevSummaryDto
import de.seuhd.campuscoffee.domain.ports.api.CoffeeBeanService
import de.seuhd.campuscoffee.domain.ports.api.CoffeeConsumptionService
import de.seuhd.campuscoffee.domain.ports.api.CoffeePriceService
import de.seuhd.campuscoffee.domain.ports.api.CoffeeRatingService
import de.seuhd.campuscoffee.domain.ports.api.ExpenseService
import de.seuhd.campuscoffee.domain.ports.api.PaymentService
import de.seuhd.campuscoffee.domain.ports.api.UserService
import de.seuhd.campuscoffee.domain.ports.system.IdGeneratorService
import de.seuhd.campuscoffee.domain.ports.system.TotpService
import de.seuhd.campuscoffee.domain.tests.TestFixtures
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.context.annotation.Profile
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestMapping

/**
 * Controller for the data management endpoints, available only in the `dev` profile.
 */
@Tag(name = "Dev data", description = "Dev-only endpoints to clear, load, and inspect test data in the database.")
@Controller
@Profile("dev")
@RequestMapping("/dev")
class DevController(
    private val userService: UserService,
    private val coffeeConsumptionService: CoffeeConsumptionService,
    private val coffeePriceService: CoffeePriceService,
    private val expenseService: ExpenseService,
    private val paymentService: PaymentService,
    private val coffeeRatingService: CoffeeRatingService,
    private val coffeeBeanService: CoffeeBeanService,
    private val idGenerator: IdGeneratorService,
    private val totpService: TotpService
) {
    /** Reports the current number of users and coffee consumptions. */
    @Operation(summary = "Report the current number of users and coffee consumptions.")
    @GetMapping("/data")
    fun count(): ResponseEntity<DevSummaryDto> =
        ResponseEntity.ok(DevSummaryDto(userService.getAll().size, coffeeConsumptionService.getAll().size))

    /** Clears the data and reloads the test fixtures, reusing the seeded ids, and reports the new counts. */
    @Operation(summary = "Replace all data with the test fixtures (users and their zeroed consumptions).")
    @PutMapping("/data")
    fun load(): ResponseEntity<DevSummaryDto> {
        // restart the id sequence so a reload assigns the fixtures the same ids
        idGenerator.reset()
        clearAll()
        val (users, consumptions) =
            TestFixtures.loadAll(
                userService,
                coffeeConsumptionService,
                coffeePriceService,
                totpService
            )
        return ResponseEntity.ok(DevSummaryDto(users, consumptions))
    }

    /** Clears all data (consumptions and users) and returns 204 No Content. */
    @Operation(summary = "Clear all data (consumptions and users).")
    @DeleteMapping("/data")
    fun clear(): ResponseEntity<Void> {
        clearAll()
        return ResponseEntity.noContent().build()
    }

    /**
     * Clears all data in foreign key order: the children that reference users (expenses and payments are
     * RESTRICT, consumptions CASCADE) before the users, then the independent price.
     */
    private fun clearAll() {
        expenseService.clear()
        paymentService.clear()
        // ratings reference beans (and users), so clear them before the beans and users
        coffeeRatingService.clear()
        coffeeConsumptionService.clear()
        userService.clear()
        coffeePriceService.clear()
        // beans last: expenses and ratings reference a bean, so clear the referencers first
        coffeeBeanService.clear()
    }
}
