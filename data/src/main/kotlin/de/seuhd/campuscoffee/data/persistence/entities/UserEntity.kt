package de.seuhd.campuscoffee.data.persistence.entities

import de.seuhd.campuscoffee.domain.model.Role
import jakarta.persistence.Column
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table

/**
 * Database entity for a member.
 */
@jakarta.persistence.Entity
@Table(name = "users")
class UserEntity : Entity() {
    @field:Column(name = LOGIN_NAME_COLUMN)
    var loginName: String? = null

    @field:Column(name = EMAIL_ADDRESS_COLUMN)
    var emailAddress: String? = null

    @field:Column(name = "first_name")
    var firstName: String? = null

    @field:Column(name = "last_name")
    var lastName: String? = null

    @field:Column(name = "password_hash")
    var passwordHash: String? = null

    @field:Enumerated(EnumType.STRING)
    @field:Column(name = "role")
    var role: Role? = null

    @field:Column(name = "active")
    var active: Boolean? = null

    @field:Column(name = CAPABILITY_TOKEN_COLUMN)
    var capabilityToken: String? = null

    companion object {
        const val LOGIN_NAME_COLUMN = "login_name"
        const val EMAIL_ADDRESS_COLUMN = "email_address"
        const val CAPABILITY_TOKEN_COLUMN = "capability_token"

        /** Names of the unique constraints, declared in the Flyway migration. */
        const val LOGIN_NAME_UNIQUE_CONSTRAINT = "uq_users_login_name"
        const val EMAIL_ADDRESS_UNIQUE_CONSTRAINT = "uq_users_email_address"
        const val CAPABILITY_TOKEN_UNIQUE_CONSTRAINT = "uq_users_capability_token"
    }
}
