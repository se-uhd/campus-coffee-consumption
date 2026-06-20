package de.seuhd.campuscoffee.api.controller

import de.seuhd.campuscoffee.api.dtos.DevSummaryDto
import de.seuhd.campuscoffee.domain.ports.IdGenerator
import de.seuhd.campuscoffee.domain.ports.api.CoffeeConsumptionService
import de.seuhd.campuscoffee.domain.ports.api.UserService
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
    private val idGenerator: IdGenerator
) {
    /** Reports the current number of users and coffee consumptions. */
    @Operation(summary = "Report the current number of users and coffee consumptions.")
    @GetMapping("/data")
    fun count(): ResponseEntity<DevSummaryDto> =
        ResponseEntity.ok(DevSummaryDto(userService.getAll().size, coffeeConsumptionService.getAll().size))

    /** Clears the data and reloads the test fixtures, reusing the seeded ids, and reports the new counts. */
    @Operation(summary = "Replace all data with the test fixtures (members and their zeroed consumptions).")
    @PutMapping("/data")
    fun load(): ResponseEntity<DevSummaryDto> {
        // restart the id sequence so a reload assigns the fixtures the same ids
        idGenerator.reset()
        clearAll()
        val (users, consumptions) = TestFixtures.loadAll(userService, coffeeConsumptionService)
        return ResponseEntity.ok(DevSummaryDto(users, consumptions))
    }

    /** Clears all data (consumptions and users) and returns 204 No Content. */
    @Operation(summary = "Clear all data (consumptions and users).")
    @DeleteMapping("/data")
    fun clear(): ResponseEntity<Void> {
        clearAll()
        return ResponseEntity.noContent().build()
    }

    /** Clears all data, deleting consumptions first because of their foreign key to users. */
    private fun clearAll() {
        coffeeConsumptionService.clear()
        userService.clear()
    }
}
