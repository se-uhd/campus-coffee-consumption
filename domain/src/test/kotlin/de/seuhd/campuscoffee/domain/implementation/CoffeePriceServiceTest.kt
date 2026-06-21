package de.seuhd.campuscoffee.domain.implementation

import de.seuhd.campuscoffee.domain.exceptions.ForbiddenException
import de.seuhd.campuscoffee.domain.exceptions.ValidationException
import de.seuhd.campuscoffee.domain.model.CoffeePrice
import de.seuhd.campuscoffee.domain.model.PriceChange
import de.seuhd.campuscoffee.domain.model.Role
import de.seuhd.campuscoffee.domain.model.User
import de.seuhd.campuscoffee.domain.ports.data.CoffeePriceDataService
import de.seuhd.campuscoffee.domain.ports.data.LedgerDataService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.LocalDateTime
import java.util.UUID

/**
 * Unit tests for CoffeePriceServiceImpl, mocking the data ports. The invariants under test: setting the
 * price is admin-only and rejects a negative value, reading the history is admin-only, and
 * ensureInitialPrice is idempotent (it creates the price only when none exists yet).
 */
class CoffeePriceServiceTest {
    private val coffeePriceDataService: CoffeePriceDataService = mock()
    private val ledgerDataService: LedgerDataService = mock()
    private val service = CoffeePriceServiceImpl(coffeePriceDataService, ledgerDataService)

    private val member =
        User(
            id = UUID(0L, 1L),
            loginName = "max",
            emailAddress = "max@se.de",
            firstName = "Max",
            lastName = "M",
            role = Role.USER,
            active = true
        )
    private val admin =
        User(
            id = UUID(0L, 99L),
            loginName = "jane",
            emailAddress = "jane@se.de",
            firstName = "Jane",
            lastName = "D",
            role = Role.ADMIN,
            active = true
        )

    @Test
    fun `getCurrent returns the seeded price`() {
        whenever(coffeePriceDataService.findCurrent()).thenReturn(CoffeePrice(amountCents = 50))

        assertThat(service.getCurrent().amountCents).isEqualTo(50)
    }

    @Test
    fun `getCurrent throws when no price has been seeded`() {
        whenever(coffeePriceDataService.findCurrent()).thenReturn(null)

        assertThrows<IllegalStateException> { service.getCurrent() }
    }

    @Test
    fun `priceHistory by an admin returns the history newest-first`() {
        whenever(ledgerDataService.priceHistory())
            .thenReturn(
                listOf(
                    PriceChange(50, LocalDateTime.now(), "jane"),
                    PriceChange(70, LocalDateTime.now(), "jane")
                )
            )

        // the data service returns oldest-first; the service reverses to newest-first
        assertThat(service.priceHistory(admin).map { it.amountCents }).containsExactly(70, 50)
    }

    @Test
    fun `setPrice by an admin creates the price when none exists yet`() {
        whenever(coffeePriceDataService.findCurrent()).thenReturn(null)
        whenever(coffeePriceDataService.upsert(any())).thenAnswer { it.arguments[0] as CoffeePrice }

        val price = service.setPrice(70, admin)

        assertThat(price.amountCents).isEqualTo(70)
    }

    @Test
    fun `setPrice by an admin updates the existing price in place`() {
        whenever(coffeePriceDataService.findCurrent()).thenReturn(CoffeePrice(id = UUID(0L, 5L), amountCents = 50))
        whenever(coffeePriceDataService.upsert(any())).thenAnswer { it.arguments[0] as CoffeePrice }

        val price = service.setPrice(70, admin)

        assertThat(price.id).isEqualTo(UUID(0L, 5L))
        assertThat(price.amountCents).isEqualTo(70)
    }

    @Test
    fun `setPrice by a non-admin throws ForbiddenException`() {
        assertThrows<ForbiddenException> { service.setPrice(70, member) }
        verify(coffeePriceDataService, never()).upsert(any())
    }

    @Test
    fun `setPrice with a negative amount throws ValidationException`() {
        assertThrows<ValidationException> { service.setPrice(-1, admin) }
        verify(coffeePriceDataService, never()).upsert(any())
    }

    @Test
    fun `ensureInitialPrice creates the price when none exists yet`() {
        whenever(coffeePriceDataService.findCurrent()).thenReturn(null)
        whenever(coffeePriceDataService.upsert(any())).thenAnswer { it.arguments[0] as CoffeePrice }

        val price = service.ensureInitialPrice(50)

        assertThat(price.amountCents).isEqualTo(50)
        verify(coffeePriceDataService).upsert(any())
    }

    @Test
    fun `ensureInitialPrice is idempotent and keeps the existing price`() {
        val existing = CoffeePrice(id = UUID(0L, 5L), amountCents = 50)
        whenever(coffeePriceDataService.findCurrent()).thenReturn(existing)

        val price = service.ensureInitialPrice(99)

        assertThat(price).isEqualTo(existing)
        verify(coffeePriceDataService, never()).upsert(any())
    }

    @Test
    fun `priceHistory by a non-admin throws ForbiddenException`() {
        assertThrows<ForbiddenException> { service.priceHistory(member) }
    }
}
