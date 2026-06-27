package de.seuhd.campuscoffee.data.persistence.events
import de.seuhd.campuscoffee.data.mapper.CoffeeConsumptionEntityMapper
import de.seuhd.campuscoffee.data.mapper.CoffeePriceEntityMapper
import de.seuhd.campuscoffee.data.mapper.ExpenseEntityMapper
import de.seuhd.campuscoffee.data.mapper.PaymentEntityMapper
import de.seuhd.campuscoffee.data.mapper.UserEntityMapper
import de.seuhd.campuscoffee.data.persistence.entities.ChangeType
import de.seuhd.campuscoffee.data.persistence.entities.CoffeeConsumptionEntity
import de.seuhd.campuscoffee.data.persistence.entities.UserEntity
import de.seuhd.campuscoffee.data.persistence.repositories.CoffeeConsumptionRepository
import de.seuhd.campuscoffee.data.persistence.repositories.CoffeePriceRepository
import de.seuhd.campuscoffee.data.persistence.repositories.ExpenseRepository
import de.seuhd.campuscoffee.data.persistence.repositories.PaymentRepository
import de.seuhd.campuscoffee.data.persistence.repositories.UserRepository
import de.seuhd.campuscoffee.domain.exceptions.ConcurrentUpdateException
import de.seuhd.campuscoffee.domain.exceptions.DeletionConflictException
import de.seuhd.campuscoffee.domain.exceptions.DuplicationException
import de.seuhd.campuscoffee.domain.model.User
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.hibernate.exception.ConstraintViolationException
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.orm.ObjectOptimisticLockingFailureException
import java.util.Optional
import java.util.UUID

/**
 * Unit tests for the [ReadModelProjector]'s dispatch and exception translation, with mocked repositories
 * and mappers so the relational outcomes can be forced deterministically. Covers the per-type insert,
 * update, and delete branches for User and CoffeeConsumption, the unknown-type guard, and the translation
 * of relational violations into the same domain exceptions the relational adapter raises.
 */
class ReadModelProjectorExceptionMappingTest {
    private val userRepository = mock<UserRepository>()
    private val coffeeConsumptionRepository = mock<CoffeeConsumptionRepository>()
    private val coffeePriceRepository = mock<CoffeePriceRepository>()
    private val expenseRepository = mock<ExpenseRepository>()
    private val paymentRepository = mock<PaymentRepository>()
    private val userMapper = mock<UserEntityMapper>()
    private val coffeeConsumptionMapper = mock<CoffeeConsumptionEntityMapper>()
    private val coffeePriceMapper = mock<CoffeePriceEntityMapper>()
    private val expenseMapper = mock<ExpenseEntityMapper>()
    private val paymentMapper = mock<PaymentEntityMapper>()

    private val projector =
        ReadModelProjector(
            userRepository,
            coffeeConsumptionRepository,
            coffeePriceRepository,
            expenseRepository,
            paymentRepository,
            userMapper,
            coffeeConsumptionMapper,
            coffeePriceMapper,
            expenseMapper,
            paymentMapper
        )

    private fun integrityViolation(constraintName: String?): DataIntegrityViolationException {
        val hibernate = mock<ConstraintViolationException>()
        whenever(hibernate.constraintName).thenReturn(constraintName)
        return DataIntegrityViolationException("could not execute statement", hibernate)
    }

    @Test
    fun `an INSERT User event saves a row in the users read table`() {
        whenever(userMapper.toEntity(any())).thenReturn(UserEntity())

        projector.apply(ChangeType.INSERT, "User", EventBodies.user(UUID.randomUUID()))

        verify(userRepository).saveAndFlush(any<UserEntity>())
    }

    @Test
    fun `an INSERT CoffeeConsumption event resolves the user and saves a row`() {
        whenever(userRepository.findById(any())).thenReturn(Optional.of(UserEntity()))
        whenever(userMapper.fromEntity(any())).thenReturn(user())
        whenever(coffeeConsumptionMapper.toEntity(any())).thenReturn(CoffeeConsumptionEntity())
        val body = EventBodies.consumption(UUID.randomUUID(), UUID.randomUUID())

        projector.apply(ChangeType.INSERT, "CoffeeConsumption", body)

        verify(coffeeConsumptionRepository).saveAndFlush(any<CoffeeConsumptionEntity>())
    }

    @Test
    fun `a duplicate unique constraint on an INSERT maps to DuplicationException`() {
        val violation = integrityViolation("uq_users_login_name")
        whenever(userMapper.toEntity(any())).thenReturn(UserEntity())
        whenever(userRepository.saveAndFlush(any<UserEntity>())).thenThrow(violation)

        assertThatThrownBy { projector.apply(ChangeType.INSERT, "User", EventBodies.user(UUID.randomUUID())) }
            .isInstanceOf(DuplicationException::class.java)
    }

    @Test
    fun `an unknown unique constraint on an INSERT propagates the original violation`() {
        val violation = integrityViolation("some_other_constraint")
        whenever(userMapper.toEntity(any())).thenReturn(UserEntity())
        whenever(userRepository.saveAndFlush(any<UserEntity>())).thenThrow(violation)

        assertThatThrownBy { projector.apply(ChangeType.INSERT, "User", EventBodies.user(UUID.randomUUID())) }
            .isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `a stale UPDATE maps an optimistic-lock failure to ConcurrentUpdateException`() {
        val id = UUID.randomUUID()
        whenever(userMapper.toEntity(any())).thenReturn(UserEntity())
        whenever(userRepository.findById(id)).thenReturn(Optional.of(UserEntity()))
        whenever(userRepository.saveAndFlush(any<UserEntity>()))
            .thenThrow(ObjectOptimisticLockingFailureException(UserEntity::class.java, id))

        assertThatThrownBy { projector.apply(ChangeType.UPDATE, "User", EventBodies.user(id)) }
            .isInstanceOf(ConcurrentUpdateException::class.java)
    }

    @Test
    fun `an UPDATE CoffeeConsumption event updates the existing read row`() {
        val id = UUID.randomUUID()
        whenever(userRepository.findById(any())).thenReturn(Optional.of(UserEntity()))
        whenever(userMapper.fromEntity(any())).thenReturn(user())
        whenever(coffeeConsumptionRepository.findById(id)).thenReturn(Optional.of(CoffeeConsumptionEntity()))
        val body = EventBodies.consumption(id, UUID.randomUUID(), count = 3)

        projector.apply(ChangeType.UPDATE, "CoffeeConsumption", body)

        verify(coffeeConsumptionRepository).saveAndFlush(any<CoffeeConsumptionEntity>())
    }

    @Test
    fun `a duplicate user_id on a CoffeeConsumption INSERT maps to DuplicationException`() {
        val violation = integrityViolation("uq_coffee_consumptions_user")
        whenever(userRepository.findById(any())).thenReturn(Optional.of(UserEntity()))
        whenever(userMapper.fromEntity(any())).thenReturn(user())
        whenever(coffeeConsumptionMapper.toEntity(any())).thenReturn(CoffeeConsumptionEntity())
        whenever(coffeeConsumptionRepository.saveAndFlush(any<CoffeeConsumptionEntity>())).thenThrow(violation)
        val body = EventBodies.consumption(UUID.randomUUID(), UUID.randomUUID())

        assertThatThrownBy { projector.apply(ChangeType.INSERT, "CoffeeConsumption", body) }
            .isInstanceOf(DuplicationException::class.java)
    }

    @Test
    fun `a DELETE CoffeeConsumption event removes the read row`() {
        val id = UUID.randomUUID()

        projector.apply(ChangeType.DELETE, "CoffeeConsumption", EventBodies.delete(id))

        verify(coffeeConsumptionRepository).deleteById(id)
    }

    @Test
    fun `a violation with no reported constraint name propagates unchanged`() {
        val violation = integrityViolation(null)
        whenever(userMapper.toEntity(any())).thenReturn(UserEntity())
        whenever(userRepository.saveAndFlush(any<UserEntity>())).thenThrow(violation)

        assertThatThrownBy { projector.apply(ChangeType.INSERT, "User", EventBodies.user(UUID.randomUUID())) }
            .isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `a DELETE removes the row from the read table`() {
        val id = UUID.randomUUID()

        projector.apply(ChangeType.DELETE, "User", EventBodies.delete(id))

        verify(userRepository).deleteById(id)
        verify(userRepository).flush()
    }

    @Test
    fun `a foreign key violation on a DELETE maps to DeletionConflictException`() {
        val id = UUID.randomUUID()
        val violation = integrityViolation("fk_some_reference")
        whenever(userRepository.flush()).thenThrow(violation)

        assertThatThrownBy { projector.apply(ChangeType.DELETE, "User", EventBodies.delete(id)) }
            .isInstanceOf(DeletionConflictException::class.java)
    }

    @Test
    fun `an INSERT CoffeeConsumption for a missing user fails with NotFoundException`() {
        whenever(userRepository.findById(any())).thenReturn(Optional.empty())
        val body = EventBodies.consumption(UUID.randomUUID(), UUID.randomUUID())

        assertThatThrownBy { projector.apply(ChangeType.INSERT, "CoffeeConsumption", body) }
            .isInstanceOf(de.seuhd.campuscoffee.domain.exceptions.NotFoundException::class.java)
    }

    @Test
    fun `an UPDATE for a missing read row fails with NotFoundException`() {
        whenever(userMapper.toEntity(any())).thenReturn(UserEntity())
        whenever(userRepository.findById(any())).thenReturn(Optional.empty())

        assertThatThrownBy { projector.apply(ChangeType.UPDATE, "User", EventBodies.user(UUID.randomUUID())) }
            .isInstanceOf(de.seuhd.campuscoffee.domain.exceptions.NotFoundException::class.java)
    }

    @Test
    fun `an event for an unknown entity type fails`() {
        assertThatThrownBy { projector.apply(ChangeType.INSERT, "Unknown", EventBodies.delete(UUID.randomUUID())) }
            .isInstanceOf(IllegalStateException::class.java)
        verify(userRepository, never()).saveAndFlush(any<UserEntity>())
    }

    @Test
    fun `a DELETE event with no id fails`() {
        assertThatThrownBy { projector.apply(ChangeType.DELETE, "User", emptyMap()) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    private fun user(): User =
        User(
            id = UUID.randomUUID(),
            loginName = "user",
            emailAddress = "user@se.de",
            firstName = "First",
            lastName = "Last"
        )
}
