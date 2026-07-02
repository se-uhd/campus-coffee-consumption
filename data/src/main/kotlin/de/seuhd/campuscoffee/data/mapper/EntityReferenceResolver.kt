package de.seuhd.campuscoffee.data.mapper

import de.seuhd.campuscoffee.data.persistence.entities.CoffeeBeanEntity
import de.seuhd.campuscoffee.data.persistence.entities.UserEntity
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Resolves a read-model `@ManyToOne` association to its referenced entity by id, for use by the entity
 * mappers (via their `uses`). Mapping an association this way sets only the foreign key: it never deep-copies
 * or mutates the referenced parent row. That distinction matters because MapStruct's default nested update
 * would call the parent mapper's `updateEntity` on the currently referenced entity, so switching a rating's
 * or an expense's bean would rewrite the *old* bean's row (renaming it onto the new bean) rather than
 * repointing the key.
 *
 * It uses [EntityManager.find] rather than `getReference`: `find` returns a fully loaded entity, so the
 * relational adapter's `fromEntity` read-back right after a save (which runs outside a session) still reads
 * the association's fields, whereas a lazy `getReference` proxy would fail to initialize once the session
 * closes. When the referenced row is already in the persistence context (the projector loads it while
 * reconstructing the event), this is a cache hit and issues no extra query.
 *
 * @param entityManager the JPA entity manager used to load the referenced entities
 */
@Component
class EntityReferenceResolver(
    private val entityManager: EntityManager
) {
    /**
     * The [UserEntity] for the given id, or null when the id is null (a nullable association, such as a pure
     * kitty adjustment's payer).
     *
     * @param userId the user id, or null for an absent association
     */
    fun userReference(userId: UUID?): UserEntity? = userId?.let { entityManager.find(UserEntity::class.java, it) }

    /**
     * The [CoffeeBeanEntity] for the given id, or null when the id is null (a non-bean outlay carries no
     * bean).
     *
     * @param beanId the bean id, or null for an absent association
     */
    fun beanReference(beanId: UUID?): CoffeeBeanEntity? =
        beanId?.let { entityManager.find(CoffeeBeanEntity::class.java, it) }
}
