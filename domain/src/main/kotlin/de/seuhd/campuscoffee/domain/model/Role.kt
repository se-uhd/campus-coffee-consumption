package de.seuhd.campuscoffee.domain.model

/**
 * Authorization role a user holds. The consumption tracker needs only two levels (CampusCoffee's
 * `MODERATOR` grant and the `Set<Role>` collection are dropped): [USER] is a regular SE@UHD member who
 * tracks their own coffee count, and [ADMIN] additionally administers members (create, edit, deactivate,
 * adjust any count, rotate capability links) and manages the money (the price, expenses, deposits, and
 * the kitty). A user holds exactly one role, mapped to a single `ROLE_USER` / `ROLE_ADMIN` Spring authority.
 */
enum class Role {
    USER,
    ADMIN
}
