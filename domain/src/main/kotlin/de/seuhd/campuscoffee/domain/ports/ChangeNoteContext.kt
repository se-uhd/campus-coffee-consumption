package de.seuhd.campuscoffee.domain.ports

import org.springframework.stereotype.Component

/**
 * Carries an admin's optional free-text note from a consumption override down to the `EventStore`,
 * which records it as the event's `note` metadata. The note is not part of the consumption's full-state
 * body, and the generic mutator/decorator `upsert` signatures must stay untouched, so it travels
 * out-of-band on a thread-local for the duration of the one upsert that records it.
 *
 * A shared bean, written by the domain service and read by the data-layer event store. The whole upsert
 * runs synchronously on one thread, so the thread-local is set just before the change and cleared
 * immediately after.
 */
@Component
class ChangeNoteContext {
    private val holder = ThreadLocal<String?>()

    /** Returns the note for the change currently being recorded, or null when none was set. */
    fun currentNote(): String? = holder.get()

    /**
     * Runs [block] with [note] visible to [currentNote], restoring the previous value afterward. Used by
     * the consumption service to wrap the single upsert whose event should carry the note.
     *
     * @param note  the note to expose for the duration of [block]
     * @param block the work to run (the note-recording upsert)
     * @return whatever [block] returns
     */
    fun <T> runWithNote(
        note: String?,
        block: () -> T
    ): T {
        val previous = holder.get()
        holder.set(note)
        return try {
            block()
        } finally {
            holder.set(previous)
        }
    }
}
