package de.seuhd.campuscoffee.api.controller

import de.seuhd.campuscoffee.api.dtos.AdminExpenseDto
import de.seuhd.campuscoffee.api.dtos.ExpenseDto
import de.seuhd.campuscoffee.api.mapper.ExpenseDtoMapper
import de.seuhd.campuscoffee.api.security.CurrentUserProvider
import de.seuhd.campuscoffee.domain.ports.api.ExpenseService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.bind.annotation.ResponseStatus
import java.util.UUID

/**
 * Admin controller for recording and correcting a user's bean purchases (JWT, admin only). The buyer is
 * the `{userId}` path variable; the body carries the weight, total, and the kitty/private split. A user
 * records their own purchases through `/api/expenses` instead; only an admin reaches these split,
 * attribution, and correction operations.
 */
@Tag(name = "Admin expenses", description = "Recording, correcting, and deleting users' bean purchases (admin only).")
@Controller
@RequestMapping("/users/{userId}/expenses")
class AdminExpenseController(
    private val expenseService: ExpenseService,
    private val expenseDtoMapper: ExpenseDtoMapper,
    private val currentUserProvider: CurrentUserProvider
) {
    /**
     * Lists the user's recorded bean purchases, so an admin can find one to correct or delete.
     *
     * @param userId the buyer whose purchases to list
     */
    @Operation(summary = "List a user's recorded bean purchases.")
    @GetMapping("")
    fun list(
        @PathVariable userId: UUID
    ): ResponseEntity<List<ExpenseDto>> {
        val admin = currentUserProvider.currentUser()
        return ResponseEntity.ok(expenseDtoMapper.toDtos(expenseService.listByBuyer(userId, admin)))
    }

    /**
     * Records a bean purchase for the user, with a kitty/private split.
     *
     * @param userId the buyer credited with the private portion
     * @param dto    the purchase (weight, total, split, optional note)
     */
    @Operation(summary = "Record a bean purchase for a user, with a kitty/private split.")
    @ResponseStatus(HttpStatus.CREATED)
    @ResponseBody
    @PostMapping("")
    fun create(
        @PathVariable userId: UUID,
        @RequestBody @Valid dto: AdminExpenseDto
    ): ExpenseDto {
        val admin = currentUserProvider.currentUser()
        val expense =
            expenseService.record(
                buyerUserId = userId,
                expenseType = requireNotNull(dto.expenseType),
                beanName = dto.beanName,
                weightGrams = dto.weightGrams,
                amountCents = requireNotNull(dto.amountCents),
                privateAmountCents = requireNotNull(dto.privateAmountCents),
                kittyAmountCents = requireNotNull(dto.kittyAmountCents),
                note = dto.note,
                actingUser = admin
            )
        return expenseDtoMapper.toDto(expense)
    }

    /**
     * Corrects a recorded bean purchase.
     *
     * @param userId    the (possibly new) buyer credited with the private portion
     * @param expenseId the id of the expense to correct
     * @param dto       the corrected purchase (weight, total, split, optional note)
     */
    @Operation(summary = "Correct a recorded bean purchase.")
    @PutMapping("/{expenseId}")
    fun update(
        @PathVariable userId: UUID,
        @PathVariable expenseId: UUID,
        @RequestBody @Valid dto: AdminExpenseDto
    ): ResponseEntity<ExpenseDto> {
        val admin = currentUserProvider.currentUser()
        val expense =
            expenseService.update(
                expenseId = expenseId,
                buyerUserId = userId,
                expenseType = requireNotNull(dto.expenseType),
                beanName = dto.beanName,
                weightGrams = dto.weightGrams,
                amountCents = requireNotNull(dto.amountCents),
                privateAmountCents = requireNotNull(dto.privateAmountCents),
                kittyAmountCents = requireNotNull(dto.kittyAmountCents),
                note = dto.note,
                actingUser = admin
            )
        return ResponseEntity.ok(expenseDtoMapper.toDto(expense))
    }

    /**
     * Deletes a recorded bean purchase.
     *
     * @param userId    the buyer the expense belongs to (path scope)
     * @param expenseId the id of the expense to delete
     */
    @Operation(summary = "Delete a recorded bean purchase.")
    @DeleteMapping("/{expenseId}")
    @Suppress("UnusedParameter") // userId is the REST path scope; the expense is deleted by its own id
    fun delete(
        @PathVariable userId: UUID,
        @PathVariable expenseId: UUID
    ): ResponseEntity<Void> {
        val admin = currentUserProvider.currentUser()
        expenseService.delete(expenseId, admin)
        return ResponseEntity.noContent().build()
    }
}
