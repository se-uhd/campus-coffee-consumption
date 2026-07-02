package de.seuhd.campuscoffee.data.persistence.projection

/**
 * Which money event a [EventProjection] came from, so each view can pick its public
 * `ActivityEntryType`.
 */
internal enum class EventProjectionType {
    /** A coffee `+1` or an admin count override (any non-undo count change). */
    CONSUMPTION,

    /** A user's own undo of a recent coffee within the grace period. */
    CONSUMPTION_CANCEL,

    /** A bean purchase; the user feed reads its private portion, the kitty feed its kitty portion. */
    EXPENSE,

    /** A user paid money in (credits the user and feeds the kitty). */
    DEPOSIT,

    /** A pure admin kitty adjustment (no user). */
    KITTY_ADJUSTMENT,

    /** The global price was changed (no balance effect; a display row only). */
    PRICE_CHANGE,

    /** A user rated a cup's beans (no balance effect; a display row carrying the bean and the value). */
    RATING
}
