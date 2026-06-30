package de.seuhd.campuscoffee.domain.model

/**
 * A user's display preference for the second card on their landing, below the big cup count. [BALANCE] shows
 * the money (their personal balance and the communal kitty), the default; [CUPS] swaps in a friendlier panel
 * of pure coffee stats (cups today, this week, and since their first cup) for a user who would rather not see
 * the prepaid-card debt figure. The choice is a per-user setting edited on the profile and follows the user
 * across devices. It changes only the user's own landing; an admin always sees the balance panel.
 */
enum class SummaryPanel {
    BALANCE,
    CUPS
}
