package de.seuhd.campuscoffee.domain.implementation

import de.seuhd.campuscoffee.domain.exceptions.ConflictException
import de.seuhd.campuscoffee.domain.exceptions.DeletionConflictException
import de.seuhd.campuscoffee.domain.exceptions.ForbiddenException
import de.seuhd.campuscoffee.domain.exceptions.MissingFieldException
import de.seuhd.campuscoffee.domain.model.CoffeeConsumption
import de.seuhd.campuscoffee.domain.model.Expense
import de.seuhd.campuscoffee.domain.model.Payment
import de.seuhd.campuscoffee.domain.model.Role
import de.seuhd.campuscoffee.domain.model.User
import de.seuhd.campuscoffee.domain.ports.api.CoffeeConsumptionService
import de.seuhd.campuscoffee.domain.ports.data.CoffeeConsumptionDataService
import de.seuhd.campuscoffee.domain.ports.data.ExpenseDataService
import de.seuhd.campuscoffee.domain.ports.data.PasswordHasherService
import de.seuhd.campuscoffee.domain.ports.data.PaymentDataService
import de.seuhd.campuscoffee.domain.ports.data.UserDataService
import de.seuhd.campuscoffee.domain.ports.system.CapabilityTokenGeneratorService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.UUID

/**
 * Unit tests for UserServiceImpl, mocking the data ports, the password hasher, the capability-token
 * generator, and the consumption service. The central invariant under test is that a password exists only
 * for an admin: a user (USER) gets none and authenticates solely with their capability link.
 */
class UserServiceTest {
    private val userDataService: UserDataService = mock()
    private val passwordHasher: PasswordHasherService = mock()
    private val capabilityTokenGenerator: CapabilityTokenGeneratorService = mock()
    private val coffeeConsumptionService: CoffeeConsumptionService = mock()
    private val coffeeConsumptionDataService: CoffeeConsumptionDataService = mock()
    private val expenseDataService: ExpenseDataService = mock()
    private val paymentDataService: PaymentDataService = mock()

    private lateinit var service: UserServiceImpl

    private val userId: UUID = UUID(0L, 1L)
    private val adminId: UUID = UUID(0L, 99L)

    // a user has no password (they authenticate with their capability token)
    private val storedUser =
        User(
            id = userId,
            loginName = "max",
            emailAddress = "max@se.de",
            firstName = "Max",
            lastName = "M",
            role = Role.USER,
            active = true,
            capabilityToken = "OLD-TOKEN",
            passwordHash = null
        )

    // an admin has a stored password hash
    private val admin =
        User(
            id = adminId,
            loginName = "jane",
            emailAddress = "jane@se.de",
            firstName = "Jane",
            lastName = "D",
            role = Role.ADMIN,
            active = true,
            capabilityToken = "ADMIN-TOKEN",
            passwordHash = "ADMIN-HASH"
        )

    // a second, distinct active admin, so the last-active-admin guard does not fire when one is changed
    private val otherAdmin =
        admin.copy(id = UUID(0L, 98L), loginName = "john", emailAddress = "john@se.de", capabilityToken = "OTHER-TOKEN")

    @BeforeEach
    fun setUp() {
        service =
            UserServiceImpl(
                userDataService,
                passwordHasher,
                capabilityTokenGenerator,
                coffeeConsumptionService,
                coffeeConsumptionDataService,
                expenseDataService,
                paymentDataService
            )
    }

    @Test
    fun `create of an admin assigns a capability token, hashes the password, and creates a consumption`() {
        whenever(capabilityTokenGenerator.newToken()).thenReturn("NEW-TOKEN")
        whenever(passwordHasher.hash("raw-password")).thenReturn("HASHED")
        whenever(userDataService.upsert(any())).thenAnswer { it.arguments[0] as User }
        whenever(coffeeConsumptionService.createForUser(any())).thenReturn(mock<CoffeeConsumption>())

        val toCreate =
            User(
                loginName = "newadmin",
                emailAddress = "newadmin@se.de",
                firstName = "New",
                lastName = "Admin",
                role = Role.ADMIN,
                password = "raw-password"
            )
        val created = service.create(toCreate, admin)

        assertThat(created.capabilityToken).isEqualTo("NEW-TOKEN")
        assertThat(created.passwordHash).isEqualTo("HASHED")
        assertThat(created.password).isNull()
        assertThat(created.role).isEqualTo(Role.ADMIN)
        verify(coffeeConsumptionService).createForUser(any())
    }

    @Test
    fun `create of a user assigns no password and creates a consumption`() {
        whenever(capabilityTokenGenerator.newToken()).thenReturn("NEW-TOKEN")
        whenever(userDataService.upsert(any())).thenAnswer { it.arguments[0] as User }
        whenever(coffeeConsumptionService.createForUser(any())).thenReturn(mock<CoffeeConsumption>())

        // a password sent for a user is ignored
        val toCreate =
            User(
                loginName = "newuser",
                emailAddress = "newuser@se.de",
                firstName = "New",
                lastName = "User",
                role = Role.USER,
                password = "ignored-password"
            )
        val created = service.create(toCreate, admin)

        assertThat(created.passwordHash).isNull()
        assertThat(created.password).isNull()
        assertThat(created.role).isEqualTo(Role.USER)
        verify(coffeeConsumptionService).createForUser(any())
    }

    @Test
    fun `create of an admin without a password throws MissingFieldException`() {
        val toCreate =
            User(
                loginName = "newadmin",
                emailAddress = "newadmin@se.de",
                firstName = "New",
                lastName = "Admin",
                role = Role.ADMIN
            )

        assertThrows<MissingFieldException> { service.create(toCreate, admin) }
        verify(coffeeConsumptionService, never()).createForUser(any())
    }

    @Test
    fun `create by a non-admin throws ForbiddenException`() {
        val toCreate = User(loginName = "new", emailAddress = "new@se.de", firstName = "New", lastName = "User")

        assertThrows<ForbiddenException> { service.create(toCreate, storedUser) }
        verify(userDataService, never()).upsert(any())
    }

    @Test
    fun `create defaults the role to USER and the account to active`() {
        whenever(capabilityTokenGenerator.newToken()).thenReturn("NEW-TOKEN")
        whenever(userDataService.upsert(any())).thenAnswer { it.arguments[0] as User }
        whenever(coffeeConsumptionService.createForUser(any())).thenReturn(mock<CoffeeConsumption>())

        val toCreate =
            User(
                loginName = "norole",
                emailAddress = "norole@se.de",
                firstName = "No",
                lastName = "Role",
                role = null,
                active = null
            )
        val created = service.create(toCreate, admin)

        assertThat(created.role).isEqualTo(Role.USER)
        assertThat(created.active).isTrue()
        assertThat(created.passwordHash).isNull()
    }

    @Test
    fun `update by a non-admin self keeps the stored role and ignores an attempted escalation`() {
        whenever(userDataService.getById(userId)).thenReturn(storedUser)
        whenever(userDataService.upsert(any())).thenAnswer { it.arguments[0] as User }

        val attempted = storedUser.copy(role = Role.ADMIN, firstName = "Maximilian")
        val updated = service.update(attempted, storedUser)

        assertThat(updated.role).isEqualTo(Role.USER)
        assertThat(updated.firstName).isEqualTo("Maximilian")
        assertThat(updated.passwordHash).isNull()
    }

    @Test
    fun `update promoting a user to admin requires and hashes a password`() {
        whenever(userDataService.getById(userId)).thenReturn(storedUser)
        whenever(passwordHasher.hash("admin-pw")).thenReturn("PROMOTED-HASH")
        whenever(userDataService.upsert(any())).thenAnswer { it.arguments[0] as User }

        val attempted = storedUser.copy(role = Role.ADMIN, password = "admin-pw")
        val updated = service.update(attempted, admin)

        assertThat(updated.role).isEqualTo(Role.ADMIN)
        assertThat(updated.passwordHash).isEqualTo("PROMOTED-HASH")
    }

    @Test
    fun `update promoting a user to admin without a password throws MissingFieldException`() {
        whenever(userDataService.getById(userId)).thenReturn(storedUser)

        assertThrows<MissingFieldException> { service.update(storedUser.copy(role = Role.ADMIN), admin) }
    }

    @Test
    fun `update demoting an admin to a user clears the password`() {
        whenever(userDataService.getById(adminId)).thenReturn(admin)
        whenever(userDataService.upsert(any())).thenAnswer { it.arguments[0] as User }
        // a second active admin remains, so demoting this one is allowed (it is not the last active admin)
        whenever(userDataService.getAll()).thenReturn(listOf(admin, otherAdmin))

        val updated = service.update(admin.copy(role = Role.USER), admin)

        assertThat(updated.role).isEqualTo(Role.USER)
        assertThat(updated.passwordHash).isNull()
    }

    @Test
    fun `update of an admin with a new password re-hashes it`() {
        whenever(userDataService.getById(adminId)).thenReturn(admin)
        whenever(passwordHasher.hash("new-raw")).thenReturn("NEW-HASH")
        whenever(userDataService.upsert(any())).thenAnswer { it.arguments[0] as User }

        val updated = service.update(admin.copy(password = "new-raw"), admin)

        assertThat(updated.passwordHash).isEqualTo("NEW-HASH")
        assertThat(updated.password).isNull()
    }

    @Test
    fun `update of an admin without a new password keeps the stored hash`() {
        whenever(userDataService.getById(adminId)).thenReturn(admin)
        whenever(userDataService.upsert(any())).thenAnswer { it.arguments[0] as User }

        val partial = admin.copy(firstName = "Janet", capabilityToken = null, passwordHash = null, password = null)
        val updated = service.update(partial, admin)

        assertThat(updated.passwordHash).isEqualTo("ADMIN-HASH")
        assertThat(updated.firstName).isEqualTo("Janet")
    }

    @Test
    fun `update with a partial body keeps the stored token and role and assigns a user no password`() {
        whenever(userDataService.getById(userId)).thenReturn(storedUser)
        whenever(userDataService.upsert(any())).thenAnswer { it.arguments[0] as User }

        // a partial update as the mapper produces it: no token, no role, no active flag, no password
        val partial =
            User(
                id = userId,
                loginName = "max",
                emailAddress = "max@se.de",
                firstName = "Maximilian",
                lastName = "M",
                role = null,
                active = null,
                capabilityToken = null,
                passwordHash = null,
                password = null
            )
        val updated = service.update(partial, storedUser)

        assertThat(updated.capabilityToken).isEqualTo("OLD-TOKEN")
        assertThat(updated.role).isEqualTo(Role.USER)
        assertThat(updated.active).isTrue()
        assertThat(updated.passwordHash).isNull()
    }

    @Test
    fun `upsert of a user stores no password even if one is supplied`() {
        whenever(userDataService.upsert(any())).thenAnswer { it.arguments[0] as User }

        val user =
            User(
                loginName = "preset",
                emailAddress = "preset@se.de",
                firstName = "Pre",
                lastName = "Set",
                role = Role.USER,
                active = true,
                capabilityToken = "tok",
                password = "supplied-password"
            )
        val saved = service.upsert(user)

        assertThat(saved.passwordHash).isNull()
    }

    @Test
    fun `update of another user by a non-admin throws ForbiddenException`() {
        whenever(userDataService.getById(userId)).thenReturn(storedUser)
        val stranger = storedUser.copy(id = UUID(0L, 7L), loginName = "stranger")

        assertThrows<ForbiddenException> { service.update(storedUser, stranger) }
    }

    @Test
    fun `getById of another user by a non-admin throws ForbiddenException`() {
        whenever(userDataService.getById(userId)).thenReturn(storedUser)
        val stranger = storedUser.copy(id = UUID(0L, 7L), loginName = "stranger")

        assertThrows<ForbiddenException> { service.getById(userId, stranger) }
    }

    @Test
    fun `getById of own account is allowed`() {
        whenever(userDataService.getById(userId)).thenReturn(storedUser)

        val result = service.getById(userId, storedUser)

        assertThat(result.loginName).isEqualTo("max")
    }

    @Test
    fun `getById of any user by an admin is allowed`() {
        whenever(userDataService.getById(userId)).thenReturn(storedUser)

        assertThat(service.getById(userId, admin).loginName).isEqualTo("max")
    }

    @Test
    fun `getByLoginName of own account is allowed`() {
        whenever(userDataService.getByLoginName("max")).thenReturn(storedUser)

        assertThat(service.getByLoginName("max", storedUser).loginName).isEqualTo("max")
    }

    @Test
    fun `getByLoginName of another user by a non-admin throws ForbiddenException`() {
        whenever(userDataService.getByLoginName("max")).thenReturn(storedUser)
        val stranger = storedUser.copy(id = UUID(0L, 7L), loginName = "stranger")

        assertThrows<ForbiddenException> { service.getByLoginName("max", stranger) }
    }

    @Test
    fun `getByLoginName without an acting user delegates to the data service`() {
        whenever(userDataService.getByLoginName("max")).thenReturn(storedUser)

        assertThat(service.getByLoginName("max").loginName).isEqualTo("max")
    }

    @Test
    fun `rotateCapabilityToken by an admin issues a new token`() {
        whenever(userDataService.getById(userId)).thenReturn(storedUser)
        whenever(capabilityTokenGenerator.newToken()).thenReturn("ROTATED-TOKEN")
        whenever(userDataService.upsert(any())).thenAnswer { it.arguments[0] as User }

        val rotated = service.rotateCapabilityToken(userId, admin)

        assertThat(rotated.capabilityToken).isEqualTo("ROTATED-TOKEN")
    }

    @Test
    fun `rotateCapabilityToken by a non-admin throws ForbiddenException`() {
        assertThrows<ForbiddenException> { service.rotateCapabilityToken(userId, storedUser) }
        verify(userDataService, never()).upsert(any())
    }

    @Test
    fun `findByCapabilityToken delegates to the data service`() {
        whenever(userDataService.findByCapabilityToken("a-token")).thenReturn(storedUser)

        assertThat(service.findByCapabilityToken("a-token")).isEqualTo(storedUser)
    }

    @Test
    fun `findByCapabilityToken returns null for an unknown token`() {
        whenever(userDataService.findByCapabilityToken("unknown")).thenReturn(null)

        assertThat(service.findByCapabilityToken("unknown")).isNull()
    }

    @Test
    fun `upsert of an existing admin keeps the supplied capability token and the stored hash`() {
        whenever(userDataService.getById(adminId)).thenReturn(admin)
        whenever(userDataService.upsert(any())).thenAnswer { it.arguments[0] as User }

        // an update carrying an explicit token keeps that token (the elvis does not fall through)
        val saved = service.upsert(admin.copy(capabilityToken = "KEEP-THIS", password = null))

        assertThat(saved.capabilityToken).isEqualTo("KEEP-THIS")
        assertThat(saved.passwordHash).isEqualTo("ADMIN-HASH")
    }

    @Test
    fun `upsert of an existing user with no incoming role or active falls back to the stored values`() {
        whenever(userDataService.getById(userId)).thenReturn(storedUser)
        whenever(userDataService.upsert(any())).thenAnswer { it.arguments[0] as User }

        // an update with role and active both null falls back to the stored role/active (the elvis chain)
        val saved =
            service.upsert(
                storedUser.copy(role = null, active = null, capabilityToken = null, firstName = "Maximilian")
            )

        assertThat(saved.role).isEqualTo(Role.USER)
        assertThat(saved.active).isTrue()
        assertThat(saved.capabilityToken).isEqualTo("OLD-TOKEN")
        assertThat(saved.firstName).isEqualTo("Maximilian")
    }

    @Test
    fun `upsert of a brand-new admin with a supplied token and password hashes the password and keeps the token`() {
        whenever(passwordHasher.hash("raw")).thenReturn("HASHED")
        whenever(userDataService.upsert(any())).thenAnswer { it.arguments[0] as User }

        // role, token, and password all supplied: each elvis short-circuits on the first branch
        val saved =
            service.upsert(
                User(
                    loginName = "freshadmin",
                    emailAddress = "freshadmin@se.de",
                    firstName = "Fresh",
                    lastName = "Admin",
                    role = Role.ADMIN,
                    active = true,
                    capabilityToken = "SUPPLIED",
                    password = "raw"
                )
            )

        assertThat(saved.role).isEqualTo(Role.ADMIN)
        assertThat(saved.capabilityToken).isEqualTo("SUPPLIED")
        assertThat(saved.passwordHash).isEqualTo("HASHED")
    }

    @Test
    fun `upsert of a brand-new user without a token generates one`() {
        whenever(capabilityTokenGenerator.newToken()).thenReturn("GENERATED")
        whenever(userDataService.upsert(any())).thenAnswer { it.arguments[0] as User }

        val saved =
            service.upsert(
                User(
                    loginName = "fresh",
                    emailAddress = "fresh@se.de",
                    firstName = "Fresh",
                    lastName = "User",
                    role = Role.USER,
                    active = false,
                    capabilityToken = null
                )
            )

        assertThat(saved.capabilityToken).isEqualTo("GENERATED")
        // an explicit active flag is preserved on a create
        assertThat(saved.active).isFalse()
    }

    @Test
    fun `delete of a pristine user removes the user`() {
        whenever(coffeeConsumptionDataService.getByUserId(userId))
            .thenReturn(CoffeeConsumption(user = storedUser, count = 0))
        whenever(expenseDataService.getAllByBuyer(userId)).thenReturn(emptyList())
        whenever(paymentDataService.getAllByUser(userId)).thenReturn(emptyList())

        service.delete(userId)

        verify(userDataService).delete(userId)
    }

    @Test
    fun `delete of a user with a non-zero count throws DeletionConflictException`() {
        whenever(coffeeConsumptionDataService.getByUserId(userId))
            .thenReturn(CoffeeConsumption(user = storedUser, count = 3))

        assertThrows<DeletionConflictException> { service.delete(userId) }
        verify(userDataService, never()).delete(any())
    }

    @Test
    fun `delete of a user with an expense throws DeletionConflictException`() {
        whenever(coffeeConsumptionDataService.getByUserId(userId))
            .thenReturn(CoffeeConsumption(user = storedUser, count = 0))
        whenever(expenseDataService.getAllByBuyer(userId))
            .thenReturn(
                listOf(
                    Expense(
                        buyer = storedUser,
                        weightGrams = 1,
                        amountCents = 1,
                        privateAmountCents = 1,
                        kittyAmountCents = 0
                    )
                )
            )

        assertThrows<DeletionConflictException> { service.delete(userId) }
        verify(userDataService, never()).delete(any())
    }

    @Test
    fun `delete of a user with a deposit throws DeletionConflictException`() {
        whenever(coffeeConsumptionDataService.getByUserId(userId))
            .thenReturn(CoffeeConsumption(user = storedUser, count = 0))
        whenever(expenseDataService.getAllByBuyer(userId)).thenReturn(emptyList())
        whenever(paymentDataService.getAllByUser(userId))
            .thenReturn(listOf(Payment(user = storedUser, amountCents = 100)))

        assertThrows<DeletionConflictException> { service.delete(userId) }
        verify(userDataService, never()).delete(any())
    }

    @Test
    fun `update deactivating the last active admin throws ConflictException`() {
        whenever(userDataService.getById(adminId)).thenReturn(admin)
        // the admin is the only active admin in the store
        whenever(userDataService.getAll()).thenReturn(listOf(admin, storedUser))

        assertThrows<ConflictException> { service.update(admin.copy(active = false), admin) }
        verify(userDataService, never()).upsert(any())
    }

    @Test
    fun `update demoting the last active admin throws ConflictException`() {
        whenever(userDataService.getById(adminId)).thenReturn(admin)
        whenever(userDataService.getAll()).thenReturn(listOf(admin, storedUser))

        assertThrows<ConflictException> { service.update(admin.copy(role = Role.USER), admin) }
        verify(userDataService, never()).upsert(any())
    }

    @Test
    fun `update deactivating an admin while another active admin remains is allowed`() {
        whenever(userDataService.getById(adminId)).thenReturn(admin)
        whenever(userDataService.getAll()).thenReturn(listOf(admin, otherAdmin))
        whenever(userDataService.upsert(any())).thenAnswer { it.arguments[0] as User }

        val updated = service.update(admin.copy(active = false), admin)

        assertThat(updated.active).isFalse()
    }

    @Test
    fun `delete of the last active admin throws ConflictException`() {
        whenever(userDataService.getById(adminId)).thenReturn(admin)
        whenever(userDataService.getAll()).thenReturn(listOf(admin, storedUser))

        assertThrows<ConflictException> { service.delete(adminId) }
        verify(userDataService, never()).delete(any())
    }

    @Test
    fun `delete of an admin while another active admin remains passes the last-admin guard`() {
        whenever(userDataService.getById(adminId)).thenReturn(admin)
        whenever(userDataService.getAll()).thenReturn(listOf(admin, otherAdmin))
        whenever(coffeeConsumptionDataService.getByUserId(adminId))
            .thenReturn(CoffeeConsumption(user = admin, count = 0))
        whenever(expenseDataService.getAllByBuyer(adminId)).thenReturn(emptyList())
        whenever(paymentDataService.getAllByUser(adminId)).thenReturn(emptyList())

        service.delete(adminId)

        verify(userDataService).delete(adminId)
    }
}
