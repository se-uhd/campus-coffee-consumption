package de.seuhd.campuscoffee.api.dtos

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

/**
 * Request body for renaming a bean (`PUT /api/beans/{id}`, admin only): the new display [name]. The name is
 * normalized and re-checked for uniqueness among canonical beans in the domain service.
 */
data class BeanRenameDto(
    @field:NotBlank(message = "A bean name is required.")
    @field:Size(max = 200, message = "A bean name must be at most 200 characters long.")
    val name: String?
)
