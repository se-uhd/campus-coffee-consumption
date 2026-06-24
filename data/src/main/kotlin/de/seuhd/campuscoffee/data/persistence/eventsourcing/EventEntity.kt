package de.seuhd.campuscoffee.data.persistence.eventsourcing

import de.seuhd.campuscoffee.data.persistence.entities.PersistableEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import org.hibernate.annotations.Generated
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.generator.EventType
import org.hibernate.type.SqlTypes
import java.time.LocalDateTime

/**
 * One entry in the append-only event log. A full-state event: [body] holds the complete JSON state of the
 * domain object (the domain object's own id lives inside it), except for a [ChangeType.DELETE], whose body
 * holds only the id. In event sourcing mode the log is the source of truth and the read tables are a
 * projection of it.
 *
 * Like the read model entities, the id is an application-assigned UUID, so the entity extends
 * [PersistableEntity] for the assigned id and new entity handling. Events are append-only, so there is no
 * updatedAt and no optimistic locking version.
 *
 * Two metadata columns sit alongside the body rather than inside it: [createdBy] (the actor's login name,
 * a member, an admin, or `"system"`) makes a member's changes retrievable and displayable without parsing
 * the JSON, and [note] holds an admin's optional reason for a count override, a deposit, or a kitty
 * adjustment.
 */
@Entity
@Table(name = "events")
class EventEntity : PersistableEntity() {
    /**
     * Append order, assigned by the database (a strictly increasing identity column). Read-only here: it
     * defines the order the log is replayed in, because the UUID id is not monotonic. Annotated
     * [Generated] on INSERT so Hibernate reads the database-generated value back after the insert,
     * populating this field on the managed entity (otherwise a read-after-write in the same session sees a
     * null seq).
     */
    @field:Generated(event = [EventType.INSERT])
    @field:Column(name = "seq", insertable = false, updatable = false)
    var seq: Long? = null

    @field:Enumerated(EnumType.STRING)
    @field:Column(name = "change_type")
    var changeType: ChangeType? = null

    @field:Column(name = "entity_type")
    var entityType: String? = null

    @field:Column(name = "entity_version")
    var entityVersion: Long? = null

    @field:JdbcTypeCode(SqlTypes.JSON)
    @field:Column(name = "body")
    var body: Map<String, Any?>? = null

    @field:Column(name = "created_at")
    var createdAt: LocalDateTime? = null

    @field:Column(name = "created_by")
    var createdBy: String? = null

    @field:Column(name = "note")
    var note: String? = null
}
