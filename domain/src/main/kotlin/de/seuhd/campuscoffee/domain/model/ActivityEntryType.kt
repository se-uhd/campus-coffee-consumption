package de.seuhd.campuscoffee.domain.model

/**
 * What an [ActivityEntry] records. The first four appear in a user's unified activity; the kitty-funded ones
 * appear in the admin-only kitty history; [PRICE_CHANGE] appears only in the admin global activity feed.
 */
enum class ActivityEntryType {
    /** A coffee was consumed (a user `+1`, or an admin count override). */
    CONSUMPTION,

    /** A user undid a recent coffee within the grace period (reverses the matching increment). */
    CONSUMPTION_CANCEL,

    /** A user's own (private) bean purchase, which credits their balance. */
    PRIVATE_EXPENSE,

    /** A user paid money in (a deposit), which credits their balance and feeds the kitty. */
    DEPOSIT,

    /** The kitty-funded portion of a bean purchase, which draws the kitty down. */
    KITTY_EXPENSE,

    /** An admin adjustment of the kitty (an initial float or a correction). */
    KITTY_ADJUSTMENT,

    /** The global price per cup was changed (admin global feed only; it moves no single balance). */
    PRICE_CHANGE,

    /** A user rated the beans of a cup (one to five); it moves no balance, so it carries no money effect. */
    RATING
}
