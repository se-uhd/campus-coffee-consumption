package de.seuhd.campuscoffee.api.dtos

/**
 * DTO reporting the number of users and coffee consumptions currently stored (used only in the `dev`
 * profile).
 */
data class DevSummaryDto(
    val users: Int,
    val consumptions: Int
)
