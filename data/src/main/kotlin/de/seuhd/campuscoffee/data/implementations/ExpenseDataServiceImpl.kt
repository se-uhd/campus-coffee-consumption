package de.seuhd.campuscoffee.data.implementations

import de.seuhd.campuscoffee.data.mapper.ExpenseEntityMapper
import de.seuhd.campuscoffee.data.persistence.entities.ExpenseEntity
import de.seuhd.campuscoffee.data.persistence.repositories.ExpenseRepository
import de.seuhd.campuscoffee.domain.model.Expense
import de.seuhd.campuscoffee.domain.ports.data.ExpenseDataService
import de.seuhd.campuscoffee.domain.ports.system.IdGeneratorService
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Data-layer adapter implementing the expense data service port. Persistence only (business logic lives in
 * the domain service layer). Expenses have no unique key, so no constraint mappings are declared; the
 * split-sum invariant is validated in the domain service and backed by a database CHECK.
 */
@Service
class ExpenseDataServiceImpl(
    repository: ExpenseRepository,
    entityMapper: ExpenseEntityMapper,
    idGenerator: IdGeneratorService
) : CrudDataServiceImpl<Expense, ExpenseEntity, ExpenseRepository, UUID>(
        repository,
        entityMapper,
        Expense::class.java,
        emptySet(),
        idGenerator
    ),
    ExpenseDataService {
    override fun getAllByBuyer(userId: UUID): List<Expense> =
        repository.findByBuyerId(userId).map { mapper.fromEntity(it) }
}
