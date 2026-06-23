package de.seuhd.campuscoffee.data.implementations

import de.seuhd.campuscoffee.data.mapper.PaymentEntityMapper
import de.seuhd.campuscoffee.data.persistence.entities.PaymentEntity
import de.seuhd.campuscoffee.data.persistence.repositories.PaymentRepository
import de.seuhd.campuscoffee.domain.model.Payment
import de.seuhd.campuscoffee.domain.ports.IdGenerator
import de.seuhd.campuscoffee.domain.ports.data.PaymentDataService
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Data-layer adapter implementing the payment data service port. Persistence only (business logic lives in
 * the domain service layer). Payments have no unique key, so no constraint mappings are declared.
 */
@Service
class PaymentDataServiceImpl(
    repository: PaymentRepository,
    entityMapper: PaymentEntityMapper,
    idGenerator: IdGenerator
) : CrudDataServiceImpl<Payment, PaymentEntity, PaymentRepository, UUID>(
        repository,
        entityMapper,
        Payment::class.java,
        emptySet(),
        idGenerator
    ),
    PaymentDataService {
    override fun getAllByUser(userId: UUID): List<Payment> =
        repository.findByUserId(userId).map { mapper.fromEntity(it) }
}
