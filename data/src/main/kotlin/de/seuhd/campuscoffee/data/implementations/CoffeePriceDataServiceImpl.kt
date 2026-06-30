package de.seuhd.campuscoffee.data.implementations

import de.seuhd.campuscoffee.data.mapper.CoffeePriceEntityMapper
import de.seuhd.campuscoffee.data.persistence.ConstraintMapping
import de.seuhd.campuscoffee.data.persistence.entities.CoffeePriceEntity
import de.seuhd.campuscoffee.data.persistence.repositories.CoffeePriceRepository
import de.seuhd.campuscoffee.domain.model.CoffeePrice
import de.seuhd.campuscoffee.domain.ports.data.CoffeePriceDataService
import de.seuhd.campuscoffee.domain.ports.system.IdGeneratorService
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Data-layer adapter implementing the coffee-price data service port. Persistence only (business logic
 * lives in the domain service layer). The single-row invariant is guarded by the `uq_coffee_prices_singleton`
 * unique constraint, mapped to a [de.seuhd.campuscoffee.domain.exceptions.DuplicationException] so a racing
 * double-create surfaces as a domain exception, not a raw Spring `DataIntegrityViolationException`.
 */
@Service(CoffeePriceDataServiceImpl.BEAN_NAME)
class CoffeePriceDataServiceImpl(
    repository: CoffeePriceRepository,
    entityMapper: CoffeePriceEntityMapper,
    idGenerator: IdGeneratorService
) : CrudDataServiceImpl<CoffeePrice, CoffeePriceEntity, CoffeePriceRepository, UUID>(
        repository,
        entityMapper,
        CoffeePrice::class.java,
        setOf(
            ConstraintMapping(
                { "the coffee price" },
                CoffeePriceEntity.SINGLETON_COLUMN,
                CoffeePriceEntity.SINGLETON_UNIQUE_CONSTRAINT
            )
        ),
        idGenerator
    ),
    CoffeePriceDataService {
    override fun findCurrent(): CoffeePrice? = repository.findAll().firstOrNull()?.let { mapper.fromEntity(it) }

    companion object {
        /**
         * Spring bean name of this relational adapter. The event-sourcing decorator qualifies on it to wrap
         * this bean. Without the qualifier, Spring would select the `@Primary` decorator as its own
         * [CoffeePriceDataService] delegate.
         */
        const val BEAN_NAME = "coffeePriceDataServiceImpl"
    }
}
