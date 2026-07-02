package de.seuhd.campuscoffee.data.implementations

import de.seuhd.campuscoffee.data.mapper.CoffeeRatingEntityMapper
import de.seuhd.campuscoffee.data.persistence.entities.CoffeeRatingEntity
import de.seuhd.campuscoffee.data.persistence.repositories.CoffeeRatingRepository
import de.seuhd.campuscoffee.domain.model.CoffeeRating
import de.seuhd.campuscoffee.domain.ports.data.CoffeeRatingDataService
import de.seuhd.campuscoffee.domain.ports.system.IdGeneratorService
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import java.time.LocalDateTime
import java.util.UUID

/**
 * Data-layer adapter implementing the coffee-rating data service port: persistence only. A rating has no
 * database-unique key (votes accumulate), so it declares no unique constraints.
 */
@Service(CoffeeRatingDataServiceImpl.BEAN_NAME)
class CoffeeRatingDataServiceImpl(
    repository: CoffeeRatingRepository,
    entityMapper: CoffeeRatingEntityMapper,
    idGenerator: IdGeneratorService
) : CrudDataServiceImpl<CoffeeRating, CoffeeRatingEntity, CoffeeRatingRepository, UUID>(
        repository,
        entityMapper,
        CoffeeRating::class.java,
        emptySet(),
        idGenerator
    ),
    CoffeeRatingDataService {
    override fun findCurrentWindowVote(
        userId: UUID,
        windowStart: LocalDateTime
    ): CoffeeRating? =
        repository
            .findCurrentWindowVotes(userId, windowStart, PageRequest.of(0, 1))
            .firstOrNull()
            ?.let { mapper.fromEntity(it) }

    companion object {
        /**
         * Spring bean name of this relational adapter. The event-sourcing decorator qualifies on it so the
         * `@Primary` decorator does not select itself as its own delegate.
         */
        const val BEAN_NAME = "coffeeRatingDataServiceImpl"
    }
}
