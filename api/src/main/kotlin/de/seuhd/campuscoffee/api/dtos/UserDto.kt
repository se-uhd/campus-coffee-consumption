package de.seuhd.campuscoffee.api.dtos

import com.fasterxml.jackson.annotation.JsonProperty
import de.seuhd.campuscoffee.domain.model.Role
import de.seuhd.campuscoffee.domain.model.SummaryPanel
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.time.LocalDateTime
import java.util.UUID

/**
 * DTO for user metadata. Properties are nullable, so a request body that omits a field deserializes and
 * is then rejected by bean validation; the controller validates the DTO before it is mapped to a
 * [de.seuhd.campuscoffee.domain.model.User].
 *
 * [password] is write-only and applies only to an admin: it is required when creating or promoting an admin
 * (at least 24 characters, with a lowercase letter, an uppercase letter, and a digit), and a user (USER)
 * never has one; a user authenticates solely with their capability link, so any password sent for a user
 * is ignored. No response serializes it (and the stored hash is never exposed at all). [role] and [active]
 * appear in responses and may be set by an
 * admin; a non-admin self-update that sends them is ignored by the domain. [capabilityUrl] is read-only:
 * the assembled "your coffee link" the controller fills in from the user's secret token (the raw token
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
    // for a user
    @field:Size(
        min = MIN_PASSWORD_LENGTH,
        max = MAX_PASSWORD_LENGTH,
        message = "Password must be between 24 and 255 characters long."
    )
    @field:Pattern(
        regexp = PASSWORD_COMPLEXITY_PATTERN,
        message = "Password must contain at least one lowercase letter, one uppercase letter, and one digit."
    )
    @field:JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    val password: String? = null,
    val role: Role? = null,
    val active: Boolean? = null,
    // the user's own landing-panel preference (BALANCE / CUPS). Nullable accept-or-keep (omitted keeps the
    // stored value); a read always carries it. Preserved on an admin save; the user edits it on their profile.
    val summaryPanel: SummaryPanel? = null,
    // server-assigned "your coffee link"; present in responses and ignored by the mapper on input
    val capabilityUrl: String? = null
) : Dto<UUID>
