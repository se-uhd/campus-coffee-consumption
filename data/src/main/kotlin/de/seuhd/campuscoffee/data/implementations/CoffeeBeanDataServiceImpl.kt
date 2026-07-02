package de.seuhd.campuscoffee.data.implementations

import de.seuhd.campuscoffee.data.mapper.CoffeeBeanEntityMapper
import de.seuhd.campuscoffee.data.persistence.ConstraintMapping
import de.seuhd.campuscoffee.data.persistence.entities.CoffeeBeanEntity
import de.seuhd.campuscoffee.data.persistence.repositories.CoffeeBeanRepository
import de.seuhd.campuscoffee.domain.model.CoffeeBean
import de.seuhd.campuscoffee.domain.ports.data.CoffeeBeanDataService
import de.seuhd.campuscoffee.domain.ports.system.IdGeneratorService
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Data-layer adapter implementing the coffee-bean data service port: persistence only. The bean-name
 * uniqueness (one live bean per name) is reported as a
 * [de.seuhd.campuscoffee.domain.exceptions.DuplicationException] on `name`.
 */
@Service(CoffeeBeanDataServiceImpl.BEAN_NAME)
class CoffeeBeanDataServiceImpl(
    repository: CoffeeBeanRepository,
    entityMapper: CoffeeBeanEntityMapper,
    idGenerator: IdGeneratorService
) : CrudDataServiceImpl<CoffeeBean, CoffeeBeanEntity, CoffeeBeanRepository, UUID>(
        repository,
        entityMapper,
        CoffeeBean::class.java,
        setOf(ConstraintMapping({ it.name }, CoffeeBeanEntity.NAME_COLUMN, CoffeeBeanEntity.NAME_UNIQUE_CONSTRAINT)),
        idGenerator
    ),
    CoffeeBeanDataService {
    override fun findActiveByName(name: String): CoffeeBean? =
        repository.findActiveByName(name)?.let { mapper.fromEntity(it) }

    override fun findMostRecentlyPurchased(): CoffeeBean? =
        repository.findMostRecentlyPurchased(PageRequest.of(0, 1)).firstOrNull()?.let { mapper.fromEntity(it) }

    companion object {
        /**
         * Spring bean name of this relational adapter. The event-sourcing decorator qualifies on it so the
         * `@Primary` decorator does not select itself as its own delegate.
         */
        const val BEAN_NAME = "coffeeBeanDataServiceImpl"
    }
}
