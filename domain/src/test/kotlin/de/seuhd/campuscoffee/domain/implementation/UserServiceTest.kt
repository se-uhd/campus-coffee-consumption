package de.seuhd.campuscoffee.domain.implementation

import de.seuhd.campuscoffee.domain.exceptions.ForbiddenException
import de.seuhd.campuscoffee.domain.exceptions.MissingFieldException
import de.seuhd.campuscoffee.domain.model.CoffeeConsumption
import de.seuhd.campuscoffee.domain.model.Role
import de.seuhd.campuscoffee.domain.model.User
import de.seuhd.campuscoffee.domain.ports.CapabilityTokenGenerator
import de.seuhd.campuscoffee.domain.ports.api.CoffeeConsumptionService
import de.seuhd.campuscoffee.domain.ports.data.PasswordHasher
import de.seuhd.campuscoffee.domain.ports.data.UserDataService
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
 * for an admin: a member (USER) gets none and authenticates solely with their capability link.
 */
class UserServiceTest {
    private val userDataService: UserDataService = mock()
    private val passwordHasher: PasswordHasher = mock()
    private val capabilityTokenGenerator: CapabilityTokenGenerator = mock()
    private val coffeeConsumptionService: CoffeeConsumptionService = mock()

    private lateinit var service: UserServiceImpl

    private val memberId: UUID = UUID(0L, 1L)
    private val adminId: UUID = UUID(0L, 99L)

    // a member has no password (they authenticate with their capability token)
    private val storedMember =
        User(
            id = memberId,
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

    @BeforeEach
    fun setUp() {
        service = UserServiceImpl(userDataService, passwordHasher, capabilityTokenGenerator, coffeeConsumptionService)
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
    fun `create of a member assigns no password and creates a consumption`() {
        whenever(capabilityTokenGenerator.newToken()).thenReturn("NEW-TOKEN")
        whenever(userDataService.upsert(any())).thenAnswer { it.arguments[0] as User }
        whenever(coffeeConsumptionService.createForUser(any())).thenReturn(mock<CoffeeConsumption>())

        // a password sent for a member is ignored
        val toCreate =
            User(
                loginName = "newmember",
                emailAddress = "newmember@se.de",
                firstName = "New",
                lastName = "Member",
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
        val toCreate = User(loginName = "new", emailAddress = "new@se.de", firstName = "New", lastName = "Member")

        assertThrows<ForbiddenException> { service.create(toCreate, storedMember) }
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
        whenever(userDataService.getById(memberId)).thenReturn(storedMember)
        whenever(userDataService.upsert(any())).thenAnswer { it.arguments[0] as User }

        val attempted = storedMember.copy(role = Role.ADMIN, firstName = "Maximilian")
        val updated = service.update(attempted, storedMember)

        assertThat(updated.role).isEqualTo(Role.USER)
        assertThat(updated.firstName).isEqualTo("Maximilian")
        assertThat(updated.passwordHash).isNull()
    }

    @Test
    fun `update promoting a user to admin requires and hashes a password`() {
        whenever(userDataService.getById(memberId)).thenReturn(storedMember)
        whenever(passwordHasher.hash("admin-pw")).thenReturn("PROMOTED-HASH")
        whenever(userDataService.upsert(any())).thenAnswer { it.arguments[0] as User }

        val attempted = storedMember.copy(role = Role.ADMIN, password = "admin-pw")
        val updated = service.update(attempted, admin)

        assertThat(updated.role).isEqualTo(Role.ADMIN)
        assertThat(updated.passwordHash).isEqualTo("PROMOTED-HASH")
    }

    @Test
    fun `update promoting a user to admin without a password throws MissingFieldException`() {
        whenever(userDataService.getById(memberId)).thenReturn(storedMember)

        assertThrows<MissingFieldException> { service.update(storedMember.copy(role = Role.ADMIN), admin) }
    }

    @Test
    fun `update demoting an admin to a member clears the password`() {
        whenever(userDataService.getById(adminId)).thenReturn(admin)
        whenever(userDataService.upsert(any())).thenAnswer { it.arguments[0] as User }

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
    fun `update with a partial body keeps the stored token and role and assigns a member no password`() {
        whenever(userDataService.getById(memberId)).thenReturn(storedMember)
        whenever(userDataService.upsert(any())).thenAnswer { it.arguments[0] as User }

        // a partial update as the mapper produces it: no token, no role, no active flag, no password
        val partial =
            User(
                id = memberId,
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
        val updated = service.update(partial, storedMember)

        assertThat(updated.capabilityToken).isEqualTo("OLD-TOKEN")
        assertThat(updated.role).isEqualTo(Role.USER)
        assertThat(updated.active).isTrue()
        assertThat(updated.passwordHash).isNull()
    }

    @Test
    fun `upsert of a member stores no password even if one is supplied`() {
        whenever(userDataService.upsert(any())).thenAnswer { it.arguments[0] as User }

        val member =
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
        val saved = service.upsert(member)

        assertThat(saved.passwordHash).isNull()
    }

    @Test
    fun `update of another user by a non-admin throws ForbiddenException`() {
        whenever(userDataService.getById(memberId)).thenReturn(storedMember)
        val stranger = storedMember.copy(id = UUID(0L, 7L), loginName = "stranger")

        assertThrows<ForbiddenException> { service.update(storedMember, stranger) }
    }

    @Test
    fun `getById of another user by a non-admin throws ForbiddenException`() {
        whenever(userDataService.getById(memberId)).thenReturn(storedMember)
        val stranger = storedMember.copy(id = UUID(0L, 7L), loginName = "stranger")

        assertThrows<ForbiddenException> { service.getById(memberId, stranger) }
    }

    @Test
    fun `getById of own account is allowed`() {
        whenever(userDataService.getById(memberId)).thenReturn(storedMember)

        val result = service.getById(memberId, storedMember)

        assertThat(result.loginName).isEqualTo("max")
    }

    @Test
    fun `getById of any user by an admin is allowed`() {
        whenever(userDataService.getById(memberId)).thenReturn(storedMember)

        assertThat(service.getById(memberId, admin).loginName).isEqualTo("max")
    }

    @Test
    fun `getByLoginName of own account is allowed`() {
        whenever(userDataService.getByLoginName("max")).thenReturn(storedMember)

        assertThat(service.getByLoginName("max", storedMember).loginName).isEqualTo("max")
    }

    @Test
    fun `getByLoginName of another user by a non-admin throws ForbiddenException`() {
        whenever(userDataService.getByLoginName("max")).thenReturn(storedMember)
        val stranger = storedMember.copy(id = UUID(0L, 7L), loginName = "stranger")

        assertThrows<ForbiddenException> { service.getByLoginName("max", stranger) }
    }

    @Test
    fun `getByLoginName without an acting user delegates to the data service`() {
        whenever(userDataService.getByLoginName("max")).thenReturn(storedMember)

        assertThat(service.getByLoginName("max").loginName).isEqualTo("max")
    }

    @Test
    fun `rotateCapabilityToken by an admin issues a new token`() {
        whenever(userDataService.getById(memberId)).thenReturn(storedMember)
        whenever(capabilityTokenGenerator.newToken()).thenReturn("ROTATED-TOKEN")
        whenever(userDataService.upsert(any())).thenAnswer { it.arguments[0] as User }

        val rotated = service.rotateCapabilityToken(memberId, admin)

        assertThat(rotated.capabilityToken).isEqualTo("ROTATED-TOKEN")
    }

    @Test
    fun `rotateCapabilityToken by a non-admin throws ForbiddenException`() {
        assertThrows<ForbiddenException> { service.rotateCapabilityToken(memberId, storedMember) }
        verify(userDataService, never()).upsert(any())
    }

    @Test
    fun `findByCapabilityToken delegates to the data service`() {
        whenever(userDataService.findByCapabilityToken("a-token")).thenReturn(storedMember)

        assertThat(service.findByCapabilityToken("a-token")).isEqualTo(storedMember)
    }

    @Test
    fun `findByCapabilityToken returns null for an unknown token`() {
        whenever(userDataService.findByCapabilityToken("unknown")).thenReturn(null)

        assertThat(service.findByCapabilityToken("unknown")).isNull()
    }
}
