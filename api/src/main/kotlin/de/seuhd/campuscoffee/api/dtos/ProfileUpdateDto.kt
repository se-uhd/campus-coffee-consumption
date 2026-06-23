package de.seuhd.campuscoffee.api.dtos

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size

/**
 * Request body for a member editing their own profile (`PUT /api/profile`): only the three fields a member
 * may change. It deliberately does not reuse the full user DTO, whose `loginName` (and other) constraints
 * would force a client to resend immutable, server-owned fields the profile edit drops anyway. The login
 * name is immutable, the password and role/active are admin-only, and the capability link is read-only, so
 * none of them belong in a profile edit.
 *
 * @property firstName the member's first name
 * @property lastName the member's last name
 * @property emailAddress the member's email address
 */
data class ProfileUpdateDto(
    @field:NotNull
    @field:Size(min = 1, max = 255, message = "First name must be between 1 and 255 characters long.")
    val firstName: String?,
    @field:NotNull
    @field:Size(min = 1, max = 255, message = "Last name must be between 1 and 255 characters long.")
    val lastName: String?,
    @field:NotNull
    @field:Email
    @field:Size(max = 254, message = "Email address must be at most 254 characters long.")
    val emailAddress: String?
)
