package de.seuhd.campuscoffee.api.dtos

import com.fasterxml.jackson.annotation.JsonProperty
import de.seuhd.campuscoffee.domain.model.objects.Role
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.time.LocalDateTime
import java.util.UUID

/**
 * DTO for user metadata. Properties are nullable, so a request body that omits a field deserializes and
 * is then rejected by bean validation; the controller validates the DTO before it is mapped to a
 * [de.seuhd.campuscoffee.domain.model.objects.User].
 *
 * [password] is write-only and applies only to an admin: it is required (at least 8 characters) when
 * creating or promoting an admin, and a member (USER) never has one — a member authenticates solely with
 * their capability link, so any password sent for a member is ignored. No response serializes it (and the
 * stored hash is never exposed at all). [role] and [active] appear in responses and may be set by an
 * admin; a non-admin self-update that sends them is ignored by the domain. [capabilityUrl] is read-only —
 * the assembled "your coffee link" the controller fills in from the member's secret token (the raw token
 * is never a field of its own).
 */
data class UserDto(
    override val id: UUID? = null,
    val createdAt: LocalDateTime? = null,
    val updatedAt: LocalDateTime? = null,
    @field:NotNull
    @field:Size(min = 1, max = 255, message = "Login name must be between 1 and 255 characters long.")
    @field:Pattern(regexp = "\\w+", message = "Login name can only contain word characters: [a-zA-Z_0-9]+")
    val loginName: String?,
    @field:NotNull
    @field:Email
    // @Email alone admits addresses longer than the 254-character column, which would surface as a 500
    @field:Size(max = 254, message = "Email address must be at most 254 characters long.")
    val emailAddress: String?,
    @field:NotNull
    @field:Size(min = 1, max = 255, message = "First name must be between 1 and 255 characters long.")
    val firstName: String?,
    @field:NotNull
    @field:Size(min = 1, max = 255, message = "Last name must be between 1 and 255 characters long.")
    val lastName: String?,
    // optional in the DTO (only an admin needs one); the domain requires it for an admin and ignores it
    // for a member
    @field:Size(min = 8, max = 255, message = "Password must be between 8 and 255 characters long.")
    @field:JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    val password: String? = null,
    val role: Role? = null,
    val active: Boolean? = null,
    // server-assigned "your coffee link"; present in responses and ignored by the mapper on input
    val capabilityUrl: String? = null
) : Dto<UUID>
