package de.seuhd.campuscoffee.data.implementations

import de.seuhd.campuscoffee.data.mapper.PaymentEntityMapper
import de.seuhd.campuscoffee.data.persistence.entities.PaymentEntity
import de.seuhd.campuscoffee.data.persistence.repositories.PaymentRepository
import de.seuhd.campuscoffee.domain.model.Payment
import de.seuhd.campuscoffee.domain.ports.data.PaymentDataService
import de.seuhd.campuscoffee.domain.ports.system.IdGeneratorService
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Data-layer adapter implementing the payment data service port. Persistence only (business logic lives in
 * the domain service layer). Payments have no unique key, so no constraint mappings are declared.
 */
@Service(PaymentDataServiceImpl.BEAN_NAME)
class PaymentDataServiceImpl(
    repository: PaymentRepository,
    entityMapper: PaymentEntityMapper,
    idGenerator: IdGeneratorService
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

    companion object {
        /**
         * Spring bean name of this relational adapter. The event-sourcing decorator qualifies on it to wrap
         * this bean. Without the qualifier, Spring would select the `@Primary` decorator as its own
         * [PaymentDataService] delegate.
         */
        const val BEAN_NAME = "paymentDataServiceImpl"
    }
}
