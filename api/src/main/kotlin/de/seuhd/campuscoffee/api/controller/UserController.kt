package de.seuhd.campuscoffee.api.controller

import de.seuhd.campuscoffee.api.capability.CapabilityQrResponder
import de.seuhd.campuscoffee.api.capability.CapabilityUrlFactory
import de.seuhd.campuscoffee.api.dtos.UserDto
import de.seuhd.campuscoffee.api.mapper.DtoMapper
import de.seuhd.campuscoffee.api.mapper.UserDtoMapper
import de.seuhd.campuscoffee.api.openapi.CrudOperation
import de.seuhd.campuscoffee.api.openapi.Operation.CREATE
import de.seuhd.campuscoffee.api.openapi.Operation.DELETE
import de.seuhd.campuscoffee.api.openapi.Operation.FILTER
import de.seuhd.campuscoffee.api.openapi.Operation.GET_ALL
import de.seuhd.campuscoffee.api.openapi.Operation.GET_BY_ID
import de.seuhd.campuscoffee.api.openapi.Operation.UPDATE
import de.seuhd.campuscoffee.api.openapi.Resource.USER
import de.seuhd.campuscoffee.api.security.CurrentUserProvider
import de.seuhd.campuscoffee.domain.model.User
import de.seuhd.campuscoffee.domain.model.persistedId
import de.seuhd.campuscoffee.domain.ports.api.CrudService
import de.seuhd.campuscoffee.domain.ports.api.UserService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody
import java.util.UUID

/**
 * Admin controller for managing SE@UHD members: user CRUD plus their capability links and QR codes. The
 * whole resource is admin-only (gated in the security configuration); the domain additionally enforces the
 * self-or-admin read rule. Every user response is enriched with the assembled capability URL ("coffee
 * link") so an admin can re-display and re-print any member's QR.
 *
 * A full member CRUD controller plus the capability link/QR endpoints, so it has many cohesive methods;
 * `TooManyFunctions` is suppressed.
 */
@Suppress("TooManyFunctions")
@Tag(name = "Users", description = "Admin operations for managing members and their capability links.")
@Controller
@RequestMapping("/users")
class UserController(
    private val userService: UserService,
    private val userDtoMapper: UserDtoMapper,
    private val currentUserProvider: CurrentUserProvider,
    private val capabilityUrlFactory: CapabilityUrlFactory,
    private val capabilityQrResponder: CapabilityQrResponder
) : CrudController<User, UserDto, UUID>() {
    override fun service(): CrudService<User, UUID> = userService

    override fun mapper(): DtoMapper<User, UserDto> = userDtoMapper

    @Operation
    @CrudOperation(operation = GET_ALL, resource = USER, roleRestricted = true)
    @GetMapping("")
    override fun getAll(): ResponseEntity<List<UserDto>> {
        // resolve the principal so a deactivated admin's in-flight JWT is rejected here too (the resolver
        // throws ForbiddenException for a deactivated admin), keeping the lockout uniform across endpoints
        currentUserProvider.currentUser()
        return ResponseEntity.ok(userService.getAll().map { withCapabilityUrl(it) })
    }

    @Operation
    @CrudOperation(operation = GET_BY_ID, resource = USER, roleRestricted = true)
    @GetMapping("/{id}")
    override fun getById(
        @Parameter(description = "Unique identifier of the user to retrieve.", required = true)
        @PathVariable id: UUID
    ): ResponseEntity<UserDto> =
        ResponseEntity.ok(withCapabilityUrl(userService.getById(id, currentUserProvider.currentUser())))

    @Operation
    @CrudOperation(operation = CREATE, resource = USER, roleRestricted = true)
    @PostMapping("")
    override fun create(
        @Parameter(description = "Data of the member to create, including their role.", required = true)
        @RequestBody
        @Valid dto: UserDto
    ): ResponseEntity<UserDto> {
        require(dto.id == null) { "ID must not be set when creating a new resource." }
        // creating a member is an admin operation; the domain assigns the capability token and the
        // member's consumption at count = 0
        val created = userService.create(userDtoMapper.toDomain(dto), currentUserProvider.currentUser())
        return ResponseEntity.created(getLocation(created.persistedId)).body(withCapabilityUrl(created))
    }

    @Operation
    @CrudOperation(operation = UPDATE, resource = USER, roleRestricted = true)
    @PutMapping("/{id}")
    override fun update(
        @Parameter(description = "Unique identifier of the user to update.", required = true)
        @PathVariable id: UUID,
        @Parameter(description = "Data of the user to update (profile, role, and active state).", required = true)
        @RequestBody
        @Valid dto: UserDto
    ): ResponseEntity<UserDto> {
        require(id == dto.id) { "ID in path and body do not match." }
        val updated = userService.update(userDtoMapper.toDomain(dto), currentUserProvider.currentUser())
        return ResponseEntity.ok(withCapabilityUrl(updated))
    }

    @Operation
    @CrudOperation(operation = DELETE, resource = USER, roleRestricted = true)
    @DeleteMapping("/{id}")
    override fun delete(
        @Parameter(description = "Unique identifier of the user to delete.", required = true)
        @PathVariable id: UUID
    ): ResponseEntity<Void> = super.delete(id)

    /** Retrieves the signed-in admin's own user (the admin landing default). */
    @Operation(summary = "Get the authenticated admin's own user.")
    @GetMapping("/me")
    fun me(): ResponseEntity<UserDto> = ResponseEntity.ok(withCapabilityUrl(currentUserProvider.currentUser()))

    /**
     * Retrieves the user with the given login name.
     *
     * @param loginName the login name of the user to retrieve
     */
    @Operation
    @CrudOperation(operation = FILTER, resource = USER, roleRestricted = true)
    @GetMapping("/filter")
    fun filter(
        @Parameter(description = "Login name of the user to retrieve.", required = true)
        @RequestParam("login_name") loginName: String
    ): ResponseEntity<UserDto> =
        ResponseEntity.ok(withCapabilityUrl(userService.getByLoginName(loginName, currentUserProvider.currentUser())))

    /**
     * Retrieves a member's capability link (the URL encoded in their wall QR code).
     *
     * @param id the id of the member whose link to retrieve
     */
    @Operation(summary = "Get a member's capability link (the URL encoded in their QR code).")
    @GetMapping("/{id}/link")
    fun link(
        @Parameter(description = "Unique identifier of the member.", required = true)
        @PathVariable id: UUID
    ): ResponseEntity<UserDto> =
        ResponseEntity.ok(withCapabilityUrl(userService.getById(id, currentUserProvider.currentUser())))

    /**
     * Rotates a member's capability link, issuing a new URL and invalidating the previously printed QR.
     *
     * @param id the id of the member whose link to rotate
     */
    @Operation(summary = "Rotate a member's capability link, invalidating the old QR code.")
    @PostMapping("/{id}/link/rotate")
    fun rotateLink(
        @Parameter(description = "Unique identifier of the member.", required = true)
        @PathVariable id: UUID
    ): ResponseEntity<UserDto> =
        ResponseEntity.ok(
            withCapabilityUrl(userService.rotateCapabilityToken(id, currentUserProvider.currentUser()))
        )

    /**
     * Downloads a member's capability QR code as a high-resolution PNG.
     *
     * @param id the id of the member whose QR code to download
     */
    @Operation(summary = "Download a member's capability QR code (high-resolution PNG).")
    @GetMapping("/{id}/qr.png")
    fun qrCode(
        @Parameter(description = "Unique identifier of the member.", required = true)
        @PathVariable id: UUID
    ): ResponseEntity<ByteArray> =
        capabilityQrResponder.qrResponse(userService.getById(id, currentUserProvider.currentUser()))

    /** Downloads a ZIP archive of every member's capability QR code, each entry named `<loginName>.png`. */
    @Operation(summary = "Download a ZIP archive of every member's capability QR code (one PNG per member).")
    @GetMapping("/qr.zip")
    fun qrCodesZip(): ResponseEntity<StreamingResponseBody> {
        // resolve the principal so a deactivated admin's in-flight JWT is rejected here too
        currentUserProvider.currentUser()
        return capabilityQrResponder.zipResponse(userService.getAll())
    }

    /** Maps a user to its DTO and fills in the assembled capability URL from the member's secret token. */
    private fun withCapabilityUrl(user: User): UserDto =
        userDtoMapper
            .fromDomain(
                user
            ).copy(capabilityUrl = user.capabilityToken?.let { capabilityUrlFactory.urlFor(it) })
}
