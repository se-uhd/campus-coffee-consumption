package de.seuhd.campuscoffee.data.persistence.entities

/**
 * The kind of change an [EventEntity] records: an entity was created, modified, or removed. Stored as its
 * name in the `events.change_type` column and used by the [ReadModelProjector] to decide how to apply the
 * event to the read tables.
 */
enum class ChangeType {
    INSERT,
    UPDATE,
    DELETE
}
