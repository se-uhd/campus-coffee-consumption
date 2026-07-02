package de.seuhd.campuscoffee.api.controller

import de.seuhd.campuscoffee.api.dtos.BeanMergeDto
import de.seuhd.campuscoffee.api.dtos.BeanRenameDto
import de.seuhd.campuscoffee.api.dtos.CoffeeBeanDto
import de.seuhd.campuscoffee.api.dtos.CoffeeBeanRatingsDto
import de.seuhd.campuscoffee.api.mapper.CoffeeBeanDtoMapper
import de.seuhd.campuscoffee.api.security.CurrentUserProvider
import de.seuhd.campuscoffee.domain.ports.api.CoffeeBeanService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
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
 * Controller for the coffee-bean catalog. Reading the selectable beans (`GET /api/beans`) is open to any
 * authenticated caller (the rating dropdown and the expense bean autocomplete); renaming and merging are
 * admin-only. Beans are created implicitly when a bean name is first used, so there is no create endpoint.
 */
@Tag(name = "Beans", description = "The coffee-bean catalog: the selectable list and admin rename/merge.")
@Controller
@RequestMapping("/beans")
class BeanController(
    private val coffeeBeanService: CoffeeBeanService,
    private val coffeeBeanDtoMapper: CoffeeBeanDtoMapper,
    private val currentUserProvider: CurrentUserProvider
) {
    /** Returns the selectable (live, non-merged) beans, ordered by name. */
    @Operation(summary = "List the selectable coffee beans.")
    @GetMapping("")
    fun list(): ResponseEntity<List<CoffeeBeanDto>> =
        ResponseEntity.ok(coffeeBeanDtoMapper.toDtos(coffeeBeanService.listSelectable()))

    /** Returns the bean ratings (average rating, vote count, latest rating, latest purchase). */
    @Operation(summary = "List the bean ratings.")
    @GetMapping("/ratings")
    fun ratings(): ResponseEntity<List<CoffeeBeanRatingsDto>> =
        ResponseEntity.ok(coffeeBeanDtoMapper.toRatingsDtos(coffeeBeanService.ratings()))

    /**
     * Renames a bean (admin only).
     *
     * @param id the id of the bean to rename
     * @param dto the new name
     */
    @Operation(summary = "Rename a coffee bean (admin only).")
    @PutMapping("/{id}")
    fun rename(
        @PathVariable id: UUID,
        @RequestBody @Valid dto: BeanRenameDto
    ): ResponseEntity<CoffeeBeanDto> {
        val admin = currentUserProvider.currentUser()
        val bean = coffeeBeanService.rename(id, requireNotNull(dto.name), admin)
        return ResponseEntity.ok(coffeeBeanDtoMapper.toDto(bean))
    }

    /**
     * Merges a bean into a canonical target bean (admin only).
     *
     * @param id the id of the bean to merge away
     * @param dto the id of the canonical target bean
     */
    @Operation(summary = "Merge a coffee bean into another (admin only).")
    @PostMapping("/{id}/merge")
    fun merge(
        @PathVariable id: UUID,
        @RequestBody @Valid dto: BeanMergeDto
    ): ResponseEntity<CoffeeBeanDto> {
        val admin = currentUserProvider.currentUser()
        val bean = coffeeBeanService.merge(id, requireNotNull(dto.targetBeanId), admin)
        return ResponseEntity.ok(coffeeBeanDtoMapper.toDto(bean))
    }
}
