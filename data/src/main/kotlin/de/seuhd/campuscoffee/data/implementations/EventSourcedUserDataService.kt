package de.seuhd.campuscoffee.data.implementations
import de.seuhd.campuscoffee.data.persistence.events.EventSourcedWriter
import de.seuhd.campuscoffee.domain.model.User
import de.seuhd.campuscoffee.domain.ports.data.UserDataService
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Event sourcing user data adapter, the only persistence path (the relational-only mode was dropped). A
 * Decorator around the relational [UserDataServiceImpl] (both are adapters for the same `UserDataService`
 * port, so it is marked `@Primary` to be the one the domain binds to): the `delegate` is typed against the
 * port and pinned to the relational bean with `@Qualifier(UserDataServiceImpl.BEAN_NAME)`, so the wrapper
 * shares only the interface with the wrappee. The read methods, `getByLoginName`,
 * and `findByCapabilityToken` delegate to it, while the mutating methods write event-first. The domain has
 * already cleared the raw password before it reaches the data layer, and the event mapper drops it in any
 * case; a user event keeps the stored `passwordHash`, so a login still works after a rebuild from the log.
 */
@Service
@Primary
class EventSourcedUserDataService(
    @param:Qualifier(UserDataServiceImpl.BEAN_NAME) private val delegate: UserDataService,
    private val writer: EventSourcedWriter
) : UserDataService by delegate {
    @Transactional
    override fun upsert(domain: User): User =
        writer.upsert(
            domain,
            delegate::getById,
            { id, now -> domain.copy(id = id, createdAt = now, updatedAt = now) },
            { existing, now -> domain.copy(createdAt = existing.createdAt, updatedAt = now) }
        )

    @Transactional
    override fun delete(id: UUID) = writer.delete(User::class, id, delegate::getById)

    @Transactional
    override fun clear() = writer.clear(User::class, delegate::clear)
}
