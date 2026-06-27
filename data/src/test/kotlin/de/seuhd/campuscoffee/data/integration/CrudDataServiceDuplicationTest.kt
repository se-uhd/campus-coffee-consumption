package de.seuhd.campuscoffee.data.integration

import de.seuhd.campuscoffee.data.implementations.CoffeeConsumptionDataServiceImpl
import de.seuhd.campuscoffee.data.implementations.UserDataServiceImpl
import de.seuhd.campuscoffee.domain.exceptions.DuplicationException
import de.seuhd.campuscoffee.domain.exceptions.NotFoundException
import de.seuhd.campuscoffee.domain.model.CoffeeConsumption
import de.seuhd.campuscoffee.domain.model.Role
import de.seuhd.campuscoffee.domain.model.User
import de.seuhd.campuscoffee.domain.model.persistedId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

/**
 * Integration tests for the relational [de.seuhd.campuscoffee.data.implementations.CrudDataServiceImpl]
 * adapter against a real database: a violated unique constraint maps to a [DuplicationException] on the
 * offending field, deleting a user cascades their consumption, and a missing id maps to a
 * [NotFoundException]. The concrete relational impls are injected by type so the writes hit the
 * constraint-mapping path directly (the event-sourced decorators are the `@Primary` beans for the ports).
 */
class CrudDataServiceDuplicationTest : AbstractDataIntegrationTest() {
    @Autowired
    private lateinit var userDataService: UserDataServiceImpl

    @Autowired
    private lateinit var coffeeConsumptionDataService: CoffeeConsumptionDataServiceImpl

    private fun user(
        login: String,
        email: String = "$login@se.de",
        token: String = "token-$login"
    ): User =
        User(
            loginName = login,
            emailAddress = email,
            firstName = "First",
            lastName = "Last",
            role = Role.USER,
            active = true,
            capabilityToken = token,
            passwordHash = "{noop}hash"
        )

    @Test
    fun `upsert throws DuplicationException for a duplicate login name`() {
        userDataService.upsert(user("alice", token = "t-alice"))

        assertThatThrownBy { userDataService.upsert(user("alice", email = "other@se.de", token = "t-other")) }
            .isInstanceOf(DuplicationException::class.java)
    }

    @Test
    fun `upsert throws DuplicationException for a duplicate email address`() {
        userDataService.upsert(user("bob", email = "shared@se.de", token = "t-bob"))

        assertThatThrownBy { userDataService.upsert(user("bob2", email = "shared@se.de", token = "t-bob2")) }
            .isInstanceOf(DuplicationException::class.java)
    }

    @Test
    fun `upsert throws DuplicationException for a duplicate capability token`() {
        userDataService.upsert(user("carol", token = "shared-token"))

        assertThatThrownBy { userDataService.upsert(user("carol2", token = "shared-token")) }
            .isInstanceOf(DuplicationException::class.java)
    }

    @Test
    fun `deleting a user cascades their consumption and removes both`() {
        val user = userDataService.upsert(user("dave"))
        coffeeConsumptionDataService.upsert(CoffeeConsumption(user = user, count = 0))

        // succeeds: the coffee_consumptions.user_id FK is ON DELETE CASCADE, so the consumption row goes too
        userDataService.delete(user.persistedId)

        assertThatThrownBy { userDataService.getById(user.persistedId) }
            .isInstanceOf(NotFoundException::class.java)
        assertThatThrownBy { coffeeConsumptionDataService.getByUserId(user.persistedId) }
            .isInstanceOf(NotFoundException::class.java)
    }

    @Test
    fun `upsert updates an existing user and refreshes the updated timestamp`() {
        val created = userDataService.upsert(user("frank"))

        val updated = userDataService.upsert(created.copy(firstName = "Franklin"))

        assertThat(updated.firstName).isEqualTo("Franklin")
        assertThat(updated.id).isEqualTo(created.id)
    }

    @Test
    fun `getByLoginName returns the matching user and throws NotFoundException when none matches`() {
        userDataService.upsert(user("grace"))

        assertThat(userDataService.getByLoginName("grace").loginName).isEqualTo("grace")
        assertThatThrownBy { userDataService.getByLoginName("nobody") }
            .isInstanceOf(NotFoundException::class.java)
    }

    @Test
    fun `getById throws NotFoundException for an unknown id`() {
        assertThatThrownBy { userDataService.getById(UUID.randomUUID()) }
            .isInstanceOf(NotFoundException::class.java)
    }

    @Test
    fun `delete throws NotFoundException for an unknown id`() {
        assertThatThrownBy { userDataService.delete(UUID.randomUUID()) }
            .isInstanceOf(NotFoundException::class.java)
    }
}
