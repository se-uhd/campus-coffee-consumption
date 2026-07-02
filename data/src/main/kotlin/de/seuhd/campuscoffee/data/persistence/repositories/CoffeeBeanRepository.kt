package de.seuhd.campuscoffee.data.persistence.repositories

import de.seuhd.campuscoffee.data.persistence.entities.CoffeeBeanEntity
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

/**
 * Spring Data JPA repository for [CoffeeBeanEntity], keyed by [UUID]. Adds the case-insensitive canonical
 * lookup the bean-resolution path uses.
 */
interface CoffeeBeanRepository : JpaRepository<CoffeeBeanEntity, UUID> {
    /**
     * Returns the live (non-merged) bean whose name equals [name] case-insensitively, or null when none
     * matches. At most one row can match (the partial unique index on `lower(name)` where `merged_into_id`
     * is null).
     *
     * @param name the bean name to match (already normalized by the caller)
     * @return the matching canonical bean entity, or null
     */
    @Query("select b from CoffeeBeanEntity b where lower(b.name) = lower(:name) and b.mergedIntoId is null")
    fun findActiveByName(
        @Param("name") name: String
    ): CoffeeBeanEntity?

    /**
     * Returns the beans of the `BEANS` expenses newest first (paged to one for the most recent). A join over
     * the expenses read table by expense creation time; the caller takes the first.
     *
     * @param pageable the page window (the caller passes the first page of size one)
     * @return the most recently purchased beans, newest first
     */
    @Query("select e.bean from ExpenseEntity e where e.bean is not null order by e.createdAt desc")
    fun findMostRecentlyPurchased(pageable: Pageable): List<CoffeeBeanEntity>
}
