package de.seuhd.campuscoffee.data.implementations

import de.seuhd.campuscoffee.data.mapper.CoffeeConsumptionEntityMapper
import de.seuhd.campuscoffee.data.persistence.ConstraintMapping
import de.seuhd.campuscoffee.data.persistence.entities.CoffeeConsumptionEntity
import de.seuhd.campuscoffee.data.persistence.repositories.CoffeeConsumptionRepository
import de.seuhd.campuscoffee.domain.exceptions.NotFoundException
import de.seuhd.campuscoffee.domain.model.CoffeeConsumption
import de.seuhd.campuscoffee.domain.ports.data.CoffeeConsumptionDataService
import de.seuhd.campuscoffee.domain.ports.system.IdGeneratorService
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Data-layer adapter implementing the coffee-consumption data service port. Modeled on CampusCoffee's
 * review data service: persistence only (business logic lives in the domain service layer), with the
 * one-per-user uniqueness reported as a [de.seuhd.campuscoffee.domain.exceptions.DuplicationException].
 */
@Service
class CoffeeConsumptionDataServiceImpl(
    repository: CoffeeConsumptionRepository,
    entityMapper: CoffeeConsumptionEntityMapper,
    idGenerator: IdGeneratorService
) : CrudDataServiceImpl<CoffeeConsumption, CoffeeConsumptionEntity, CoffeeConsumptionRepository, UUID>(
        repository,
        entityMapper,
        CoffeeConsumption::class.java,
        // one consumption per user; a violation is reported as a DuplicationException on user_id
        setOf(
            ConstraintMapping(
                { "user ${it.user.id}" },
                "user_id",
                CoffeeConsumptionEntity.USER_UNIQUE_CONSTRAINT
            )
        ),
        idGenerator
    ),
    CoffeeConsumptionDataService {
    /**
     * Retrieves the consumption belonging to the user with the given id.
     *
     * @throws NotFoundException if the user has no consumption
     */
    override fun getByUserId(userId: UUID): CoffeeConsumption =
        findByFieldOrThrow({ repository.findByUserId(userId) }, "user_id", userId.toString())
}
