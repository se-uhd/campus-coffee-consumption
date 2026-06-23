package de.seuhd.campuscoffee.data.persistence.eventsourcing

import de.seuhd.campuscoffee.data.persistence.repositories.CoffeePriceRepository
import de.seuhd.campuscoffee.data.persistence.repositories.ExpenseRepository
import de.seuhd.campuscoffee.data.persistence.repositories.PaymentRepository
import de.seuhd.campuscoffee.domain.model.CoffeeConsumption
import de.seuhd.campuscoffee.domain.model.CoffeePrice
import de.seuhd.campuscoffee.domain.model.Expense
import de.seuhd.campuscoffee.domain.model.Payment
import de.seuhd.campuscoffee.domain.model.Role
import de.seuhd.campuscoffee.domain.model.User
import de.seuhd.campuscoffee.domain.ports.data.CoffeePriceDataService
import de.seuhd.campuscoffee.domain.ports.data.ExpenseDataService
import de.seuhd.campuscoffee.domain.ports.data.PaymentDataService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

/**
 * Integration test for the foreign-key order of the data reset path that [de.seuhd.campuscoffee]'s
 * `FixtureStartupLoader.clearAll` and the test-base teardown both follow: the financial children (expenses
 * and payments, whose user foreign keys are RESTRICT) and the cascading consumptions must be cleared before
 * the users, then the independent price. This seeds all five logged entity types against a real PostgreSQL
 * schema (with the real RESTRICT constraints) and asserts the documented clear order raises no foreign-key
 * violation and empties every read table and the event log, which a wrong order (clearing users first)
 * would not.
 */
class ClearAllForeignKeyOrderIntegrationTest : AbstractEventSourcingDataIntegrationTest() {
    @Autowired
    private lateinit var coffeePriceDataService: CoffeePriceDataService

    @Autowired
    private lateinit var expenseDataService: ExpenseDataService

    @Autowired
    private lateinit var paymentDataService: PaymentDataService

    @Autowired
    private lateinit var expenseRepository: ExpenseRepository

    @Autowired
    private lateinit var paymentRepository: PaymentRepository

    @Autowired
    private lateinit var coffeePriceRepository: CoffeePriceRepository

    private fun seedMember(): User =
        userDataService.upsert(
            User(
                loginName = "member",
                emailAddress = "member@se.de",
                firstName = "Mem",
                lastName = "Ber",
                role = Role.USER,
                active = true,
                capabilityToken = "token-member-${UUID.randomUUID()}",
                passwordHash = "{noop}hash"
            )
        )

    // Clears in the same foreign-key order as FixtureStartupLoader.clearAll: the RESTRICT children (expenses
    // and payments) and the cascading consumptions before the users they reference, then the independent
    // price.
    private fun clearAllInFixtureLoaderOrder() {
        expenseDataService.clear()
        paymentDataService.clear()
        coffeeConsumptionDataService.clear()
        userDataService.clear()
        coffeePriceDataService.clear()
    }

    @Test
    fun `clearing all five logged entity types in foreign-key order empties every table and the event log`() {
        // seed every logged entity type so all the read tables and the children's RESTRICT user FKs are
        // populated before the clear: a user, their consumption, the price, an expense they bought, and a
        // settlement they paid
        val member = seedMember()
        coffeeConsumptionDataService.upsert(CoffeeConsumption(user = member, count = 2))
        coffeePriceDataService.upsert(CoffeePrice(amountCents = 50))
        expenseDataService.upsert(
            Expense(
                buyer = member,
                weightGrams = 1000,
                amountCents = 900,
                privateAmountCents = 900,
                kittyAmountCents = 0
            )
        )
        paymentDataService.upsert(Payment(user = member, amountCents = 1000))

        // every read table is populated and the log has the five writes
        assertThat(userRepository.count()).isEqualTo(1)
        assertThat(coffeeConsumptionRepository.count()).isEqualTo(1)
        assertThat(coffeePriceRepository.count()).isEqualTo(1)
        assertThat(expenseRepository.count()).isEqualTo(1)
        assertThat(paymentRepository.count()).isEqualTo(1)
        assertThat(eventRepository.count()).isEqualTo(5)

        // the documented FK order clears the children before the users, so the RESTRICT FKs never block:
        // no DataIntegrityViolation / foreign-key violation is raised
        assertThatCode { clearAllInFixtureLoaderOrder() }.doesNotThrowAnyException()

        // every read table is empty and the event log is empty (clear() empties the log as well)
        assertThat(userRepository.count()).isZero()
        assertThat(coffeeConsumptionRepository.count()).isZero()
        assertThat(coffeePriceRepository.count()).isZero()
        assertThat(expenseRepository.count()).isZero()
        assertThat(paymentRepository.count()).isZero()
        assertThat(eventRepository.count()).isZero()
    }
}
