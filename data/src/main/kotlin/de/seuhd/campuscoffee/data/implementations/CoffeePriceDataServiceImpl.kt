package de.seuhd.campuscoffee.data.implementations

import de.seuhd.campuscoffee.data.constraints.ConstraintMapping
import de.seuhd.campuscoffee.data.mapper.CoffeePriceEntityMapper
import de.seuhd.campuscoffee.data.persistence.entities.CoffeePriceEntity
import de.seuhd.campuscoffee.data.persistence.repositories.CoffeePriceRepository
import de.seuhd.campuscoffee.domain.model.CoffeePrice
import de.seuhd.campuscoffee.domain.ports.IdGenerator
import de.seuhd.campuscoffee.domain.ports.data.CoffeePriceDataService
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Data-layer adapter implementing the coffee-price data service port. Persistence only (business logic
 * lives in the domain service layer). The price has no unique key — its singleton-ness is a service rule —
 * so it declares no constraint mappings.
 */
@Service
class CoffeePriceDataServiceImpl(
    repository: CoffeePriceRepository,
    entityMapper: CoffeePriceEntityMapper,
    idGenerator: IdGenerator
) : CrudDataServiceImpl<CoffeePrice, CoffeePriceEntity, CoffeePriceRepository, UUID>(
        repository,
        entityMapper,
        CoffeePrice::class.java,
        emptySet<ConstraintMapping<CoffeePrice>>(),
        idGenerator
    ),
    CoffeePriceDataService {
    override fun findCurrent(): CoffeePrice? = repository.findAll().firstOrNull()?.let { mapper.fromEntity(it) }
}
