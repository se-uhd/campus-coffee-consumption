package de.seuhd.campuscoffee.tests.system

import de.seuhd.campuscoffee.Application
import de.seuhd.campuscoffee.domain.model.User
import de.seuhd.campuscoffee.domain.ports.api.CoffeeConsumptionService
import de.seuhd.campuscoffee.domain.ports.api.CoffeePriceService
import de.seuhd.campuscoffee.domain.ports.api.ExpenseService
import de.seuhd.campuscoffee.domain.ports.api.PaymentService
import de.seuhd.campuscoffee.domain.ports.api.UserService
import de.seuhd.campuscoffee.domain.ports.system.TotpService
import de.seuhd.campuscoffee.domain.tests.TestFixtures
import de.seuhd.campuscoffee.tests.SystemTestUtils.configureClient
import de.seuhd.campuscoffee.tests.SystemTestUtils.getPostgresContainer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import java.security.KeyPairGenerator
import java.util.Base64

/**
 * Base for system tests that boot the full application under the `prod` profile against a PostgreSQL
 * testcontainer. Unlike [AbstractSystemTest] (default profile), this exercises the prod-only security
 * configuration: the strict CSP, the `Secure` session cookie, the `WeakDevSecretGuard`/`PublicBaseUrlGuard`
 * fail-fast guards, and the locked-down dev/actuator surface. It supplies the prod-required values the prod
 * profile has no fallback for: the Testcontainers datasource, a non-dev JWT secret, a public https base URL,
 * and a freshly generated RSA login key (the shared test key is the committed dev fallback that
 * `WeakDevSecretGuard` refuses under prod). Fixtures are seeded via the services since prod loads none.
 */
@SpringBootTest(
    classes = [Application::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@ActiveProfiles("prod")
abstract class AbstractProdProfileSystemTest {
    @Autowired
    protected lateinit var userService: UserService

    @Autowired
    protected lateinit var coffeeConsumptionService: CoffeeConsumptionService

    @Autowired
    protected lateinit var coffeePriceService: CoffeePriceService

    @Autowired
    protected lateinit var expenseService: ExpenseService

    @Autowired
    protected lateinit var paymentService: PaymentService

    @Autowired
    protected lateinit var totpService: TotpService

    @LocalServerPort
    private var port: Int = 0

    /** The fixture users seeded before each test. */
    protected lateinit var seededUsers: List<User>

    @BeforeEach
    fun beforeEach() {
        clearAll()
        seededUsers = TestFixtures.createUserFixtures(userService)
        // 2FA is required for admins, so enroll the fixture admin (with the deterministic secret) so an admin
        // login yields a full-scope token; the admin login helper supplies a matching code
        TestFixtures.enrollAdminFixture(userService, totpService)
        TestFixtures.createConsumptionFixtures(coffeeConsumptionService, seededUsers)
        TestFixtures.createPriceFixture(coffeePriceService)
        configureClient(port)
    }

    @AfterEach
    fun afterEach() {
        clearAll()
    }

    private fun clearAll() {
        expenseService.clear()
        paymentService.clear()
        coffeeConsumptionService.clear()
        userService.clear()
        coffeePriceService.clear()
    }

    protected companion object {
        protected val postgresContainer: PostgreSQLContainer<*> = getPostgresContainer().apply { start() }

        // A freshly generated 2048-bit RSA key for the prod context. The shared test key
        // (SystemTestUtils.TEST_PRIVATE_KEY_PEM) is byte-identical to the committed dev fallback, which
        // WeakDevSecretGuard refuses under the prod profile, so prod must get a key it has never seen.
        private val freshLoginKeyPem: String =
            run {
                val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
                val body = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(keyPair.private.encoded)
                "-----BEGIN PRIVATE KEY-----\n$body\n-----END PRIVATE KEY-----\n"
            }

        @JvmStatic
        @DynamicPropertySource
        fun configureProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgresContainer::getJdbcUrl)
            registry.add("spring.datasource.username", postgresContainer::getUsername)
            registry.add("spring.datasource.password", postgresContainer::getPassword)
            // prod has no fallback for these; supply non-dev values so WeakDevSecretGuard accepts the boot
            registry.add("campus-coffee.jwt.secret") { "prod-test-jwt-secret-at-least-32-bytes-long" }
            registry.add("campus-coffee.login-encryption.private-key-pem") { freshLoginKeyPem }
            // a public https origin so PublicBaseUrlGuard accepts the boot
            registry.add("campus-coffee.app.base-url") { "https://coffee.se.uni-heidelberg.de" }
            // prod requires a TOTP encryption key with no fallback; supply a non-dev value (the dev default is
            // rejected by TotpEncryptionKeyGuard under prod, so give it a key it has never seen)
            registry.add("campus-coffee.totp.encryption-key") { "prod-test-totp-encryption-key-not-the-dev-default" }
            // keep the (prod-enabled) login rate limiter and the TOTP guards out of the cookie/login assertions
            registry.add("campus-coffee.auth.rate-limit.enabled") { "false" }
            registry.add("campus-coffee.auth.totp.lockout-enabled") { "false" }
            registry.add("campus-coffee.auth.totp.step-reuse-guard-enabled") { "false" }
        }
    }
}
