package de.seuhd.campuscoffee.data.persistence.repositories

import de.seuhd.campuscoffee.data.persistence.entities.CoffeeRatingEntity
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime
import java.util.UUID

/**
 * Spring Data JPA repository for [CoffeeRatingEntity], keyed by [UUID]. Adds the current-window vote lookup
 * (the user's votes created at or after a window start, newest first).
 */
interface CoffeeRatingRepository : JpaRepository<CoffeeRatingEntity, UUID> {
    /**
     * Returns the user's votes created at or after [windowStart], newest first (the caller pages to one to
     * take the current window's vote).
     *
     * @param userId the owner of the votes
     * @param windowStart the current cup window's start
     * @param pageable the page window (the caller passes the first page of size one)
     * @return the matching votes, newest first
     */
    @Query(
        "select r from CoffeeRatingEntity r where r.user.id = :userId and r.createdAt >= :windowStart " +
            "order by r.createdAt desc"
    )
    fun findCurrentWindowVotes(
        @Param("userId") userId: UUID,
        @Param("windowStart") windowStart: LocalDateTime,
        pageable: Pageable
    ): List<CoffeeRatingEntity>
}
