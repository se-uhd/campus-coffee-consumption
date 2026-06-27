package de.seuhd.campuscoffee.data.persistence.eventsourcing

/**
 * Which money event a [WalkedRecord] came from, so each view can pick its public
 * `ActivityEntryType`.
 */
internal enum class WalkedKind {
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
    PRICE_CHANGE
}
