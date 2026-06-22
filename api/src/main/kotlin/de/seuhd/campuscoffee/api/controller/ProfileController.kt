package de.seuhd.campuscoffee.api.controller

import de.seuhd.campuscoffee.api.capability.CapabilityQrResponder
import de.seuhd.campuscoffee.api.capability.CapabilityUrlFactory
import de.seuhd.campuscoffee.api.dtos.ProfileUpdateDto
import de.seuhd.campuscoffee.api.dtos.UserDto
import de.seuhd.campuscoffee.api.mapper.UserDtoMapper
import de.seuhd.campuscoffee.api.security.CurrentUserProvider
import de.seuhd.campuscoffee.domain.model.User
import de.seuhd.campuscoffee.domain.ports.api.UserService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping

/**
 * Self-service profile controller for the authenticated member (the capability token principal): it views
 * and edits the caller's own name and email and exposes their capability link ("your coffee link") and QR
 * download. An admin's own profile is served by the admin endpoints (`GET /api/users/me`, `PUT
 * /api/users/{id}`) under their JWT, not here; the shared SPA profile component routes accordingly.
 */
@Tag(name = "Profile", description = "The authenticated user's own profile and capability link.")
@Controller
@RequestMapping("/profile")
class ProfileController(
    private val userService: UserService,
    private val userDtoMapper: UserDtoMapper,
    private val currentUserProvider: CurrentUserProvider,
    private val capabilityUrlFactory: CapabilityUrlFactory,
    private val capabilityQrResponder: CapabilityQrResponder
) {
    /** Returns the authenticated user's own profile, including their capability link. */
    @Operation(summary = "Get the authenticated user's own profile, including their coffee link.")
    @GetMapping("")
    fun get(): ResponseEntity<UserDto> = ResponseEntity.ok(withCapabilityUrl(currentUserProvider.currentUser()))

    /**
     * Updates the authenticated member's own profile: first name, last name, and email only. The update
     * is pinned to the caller's own id and login name, and the server-owned fields a member must not change
     * through their profile (the password, role, and active flag) are dropped, so a profile edit can change
     * nothing beyond the three intended fields (a member authenticates only with their capability token and
     * never sets a password or renames their login here).
     *
     * @param dto the profile fields to update
     */
    @Operation(summary = "Update the authenticated member's own profile (first name, last name, email).")
    @PutMapping("")
    fun update(
        @Parameter(description = "The profile fields to update.", required = true)
        @RequestBody
        @Valid dto: ProfileUpdateDto
    ): ResponseEntity<UserDto> {
        val current = currentUserProvider.currentUser()
        // a member may edit only first name, last name, and email; everything else (id, login name, password,
        // role, active) is kept from the current user, so a profile edit can change nothing beyond the three
        // the @NotNull bean-validation on the DTO guarantees these are present before the handler runs
        val toUpdate =
            current.copy(
                firstName = requireNotNull(dto.firstName),
                lastName = requireNotNull(dto.lastName),
                emailAddress = requireNotNull(dto.emailAddress),
                password = null
            )
        return ResponseEntity.ok(withCapabilityUrl(userService.update(toUpdate, current)))
    }

    /** Downloads the authenticated user's own capability QR code as a high-resolution PNG. */
    @Operation(summary = "Download the member's own capability QR code (high-resolution PNG).")
    @GetMapping("/qr.png", produces = [MediaType.IMAGE_PNG_VALUE])
    fun qrCode(): ResponseEntity<ByteArray> = capabilityQrResponder.qrResponse(currentUserProvider.currentUser())

    /** Maps a user to its DTO and fills in the assembled capability URL from the member's secret token. */
    private fun withCapabilityUrl(user: User): UserDto =
        userDtoMapper
            .fromDomain(
                user
            ).copy(capabilityUrl = user.capabilityToken?.let { capabilityUrlFactory.urlFor(it) })
}
