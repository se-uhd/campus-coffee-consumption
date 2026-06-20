package de.seuhd.campuscoffee.data.persistence.entities

import jakarta.persistence.Column
import jakarta.persistence.MappedSuperclass
import jakarta.persistence.PrePersist
import jakarta.persistence.PreUpdate
import jakarta.persistence.Transient
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Base class for the relational read model entities. On top of [PersistableEntity]'s assigned id, it adds
 * the createdAt / updatedAt timestamps and sets them through the JPA lifecycle callbacks when a row is
 * written.
 */
@MappedSuperclass
abstract class Entity : PersistableEntity() {
    @field:Column(name = "created_at")
    var createdAt: LocalDateTime? = null

    @field:Column(name = "updated_at")
    var updatedAt: LocalDateTime? = null

    @field:Transient
    private var timestampsPreassigned = false

    /**
     * Marks the timestamps as already set, so the callbacks below leave [createdAt]/[updatedAt] untouched
     * and the caller's values are written as is. The read model projector calls this before
     * persisting a row, because in event sourcing mode the authoritative timestamps live in the event body
     * and must be written exactly so a rebuilt row matches the event. The default relational path never
     * calls it, so the callbacks set the timestamps as usual. It is a method, not a property, so the
     * MapStruct mappers do not treat it as a mappable field.
     */
    fun markTimestampsPreassigned() {
        timestampsPreassigned = true
    }

    /** Sets [createdAt] and [updatedAt] to the current UTC time before an insert, unless preassigned. */
    @PrePersist
    protected fun onCreate() {
        if (timestampsPreassigned) {
            return
        }
        val now = LocalDateTime.now(ZoneId.of("UTC"))
        createdAt = now
        updatedAt = now
    }

    /** Sets [updatedAt] to the current UTC time before an update, unless preassigned. */
    @PreUpdate
    protected fun onUpdate() {
        if (timestampsPreassigned) {
            return
        }
        updatedAt = LocalDateTime.now(ZoneId.of("UTC"))
    }
}
