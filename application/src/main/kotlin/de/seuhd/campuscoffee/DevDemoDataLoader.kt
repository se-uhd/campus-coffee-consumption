package de.seuhd.campuscoffee

import de.seuhd.campuscoffee.configuration.FixturesProperties
import de.seuhd.campuscoffee.domain.model.Role
import de.seuhd.campuscoffee.domain.model.User
import de.seuhd.campuscoffee.domain.model.persistedId
import de.seuhd.campuscoffee.domain.ports.api.CoffeeConsumptionService
import de.seuhd.campuscoffee.domain.ports.api.CoffeePriceService
import de.seuhd.campuscoffee.domain.ports.api.ExpenseService
import de.seuhd.campuscoffee.domain.ports.api.PaymentService
import de.seuhd.campuscoffee.domain.ports.api.UserService
import de.seuhd.campuscoffee.domain.ports.system.StartupTaskService
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

/**
 * Demo-data loader (`@Profile("dev")`, so it runs in local dev only) that layers extra users and some
 * consumption, bean-purchase and deposit history on top of the
 * five-user fixture set, so the app comes up with enough users to paginate (5 per page) and with non-empty
 * activity and change-log views. It also layers a representative
 * history (cups, own purchases, and a deposit) onto the primary fixture user `maxmustermann` (the user
 * whose capability link is the demo link printed on the wall), so its unified activity exercises every entry
 * kind. `maxmustermann` stays active.
 *
 * On top of that it gives a rich, varied history to **every** other existing user too: the fixture admin
 * `jane_doe` and the remaining fixture users (`student2023`, `lisa_lee`, `olivia_lee`) via
 * [ENRICHED_FIXTURE_LOGINS], plus each demo user with a non-empty [DemoUser] spec, so the activity and
 * change-log views are populated for almost everyone. A couple of users are deliberately left **empty** to
 * demo the empty state: the freshly created user [EMPTY_DEMO_LOGIN] (`new_user`, no history at all) and the
 * inactive demo user `hannes_schulz` (its spec carries no coffees, expenses, or deposit).
 *
 * It is deliberately a separate `@Profile("dev")` [StartupTaskService] (order [ORDER], after the fixture
 * reset+seed at 200 and the price seed at 250) rather than a change to
 * [de.seuhd.campuscoffee.domain.tests.TestFixtures]: the fixture set is asserted exactly by the system and
 * acceptance tests, which do not activate the dev profile, so this loader never runs in the
 * test context and leaves those assertions untouched.
 *
 * Determinism: the id generator is reset on every boot (the dev `reset-on-startup`), and the fixture
 * loader clears all data and reseeds before this runs, so every start reproduces the same demo data with the
 * same assigned ids. The loader is also idempotent within a run (it skips if its demo users already exist),
 * so a restart without a reset does not duplicate them.
 *
 * Actor attribution mirrors the fixtures: this runs outside any web request, so the `ActorProviderService` finds no
 * principal and every seeded event is recorded with `created_by = "SYSTEM"` (the admin/user passed as the
 * acting user only satisfies the domain authorization checks).
 */
@Component
@Profile("dev")
class DevDemoDataLoader(
    private val userService: UserService,
    private val coffeeConsumptionService: CoffeeConsumptionService,
    private val coffeePriceService: CoffeePriceService,
    private val expenseService: ExpenseService,
    private val paymentService: PaymentService,
    private val fixturesProperties: FixturesProperties
) : StartupTaskService {
    override val order = ORDER

    override fun run() {
        // The e2e sets demo-data-on-startup=false: it resets to the 5-user fixtures per test, so seeding the
        // demo users on boot is wasted startup work.
        if (!fixturesProperties.demoDataOnStartup) {
            return
        }
        loadDemoData()
    }

    /**
     * One demo user to create, with the consumption and money history to layer on top of them. [coffees]
     * is how many cups to add (each a `+1`), [ownExpenses] the user's own bean purchases (weight grams to
     * amount cents), and [depositCents] an optional deposit the user paid into the kitty.
     */
    private data class DemoUser(
        val loginName: String,
        val firstName: String,
        val lastName: String,
        val role: Role,
        val active: Boolean,
        val coffees: Int,
        val ownExpenses: List<Pair<Int, Int>>,
        val depositCents: Int?
    )

    /**
     * One admin-recorded bean-purchase variant to seed, exercising one expense split type. [totalCents] is the
     * total paid (must equal [privateCents] + [kittyCents]); [privateCents] is credited to the buyer and
     * [kittyCents] is drawn from the kitty. [label] names the variant for the log line.
     */
    private data class AdminExpenseVariant(
        val label: String,
        val weightGrams: Int,
        val totalCents: Int,
        val privateCents: Int,
        val kittyCents: Int,
        val note: String
    )

    /**
     * Creates the demo users and seeds their history, unless they already exist. Resolves the seeded
     * fixture admin (`jane_doe`) to act for the admin-only operations (deposits and the kitty adjustment).
     */
    fun loadDemoData() {
        val existingLogins = userService.getAll().mapTo(HashSet()) { it.loginName }
        if (DEMO_USERS.first().loginName in existingLogins) {
            log.info { "Skipping the dev demo data: the demo users already exist." }
            return
        }
        val admin = userService.getByLoginName(ADMIN_LOGIN)
        var coffeeTotal = 0
        var expenseTotal = 0
        var depositTotal = 0
        DEMO_USERS.forEach { spec ->
            val user = createUser(spec)
            repeat(spec.coffees) {
                coffeeConsumptionService.applyDelta(user.persistedId, 1, user)
                coffeeTotal++
            }
            spec.ownExpenses.forEach { (weightGrams, amountCents) ->
                expenseService.recordOwn(weightGrams, amountCents, "demo bean purchase", user)
                expenseTotal++
            }
            spec.depositCents?.let { amount ->
                paymentService.recordDeposit(user.persistedId, amount, "demo deposit", admin)
                depositTotal++
            }
            // deactivate last, after the user's history is seeded: a deactivated user is read-only and
            // could not record their own coffees or purchases, so an inactive demo user is created active,
            // given a realistic past, then deactivated here
            if (!spec.active) {
                userService.upsert(user.copy(active = false))
            }
        }
        // one pure kitty adjustment (an initial float) so the kitty history and balance are non-zero on a
        // fresh dev start; deposits only ever add to the kitty, so it can never go negative here
        paymentService.adjustKitty(KITTY_FLOAT_CENTS, "demo initial kitty float", admin)
        seedPrimaryDemoUserHistory(admin)
        seedFixtureUserHistories(admin)
        // the three admin-recorded expense variants (all booked against the primary demo user as buyer), so
        // the admin expense log shows every split type: private-only, kitty-only, and a private+kitty split.
        // Placed after the kitty float and all deposits above, so the kitty portion these draw (only the
        // kitty-only and the split contribute) is covered and the kitty stays >= 0.
        seedPrimaryDemoUserAdminExpenses(admin)
        // a price change and an admin count correction so the admin global activity feed shows a PRICE_CHANGE
        // row and an admin-override consumption row (every activity type appears on a fresh dev start)
        seedPriceChangeAndCorrection(admin)
        // an extra active user with no history at all, to demo the empty activity/change-log state
        createUser(EMPTY_DEMO_USER)
        log.info {
            "Seeded the dev demo data: ${DEMO_USERS.size} extra users, $coffeeTotal coffees, " +
                "$expenseTotal own purchases, $depositTotal deposits, one kitty float, plus varied " +
                "histories for ${ENRICHED_FIXTURE_LOGINS.size} other fixture users and one empty user " +
                "($EMPTY_DEMO_LOGIN)."
        }
    }

    /**
     * Gives a rich, varied history to every remaining existing fixture user (the admin and the fixture
     * users other than the primary demo user, which [seedPrimaryDemoUserHistory] already covers), so
     * nearly every user has populated activity and change-log views. The counts and amounts are varied by the
     * user's index in [ENRICHED_FIXTURE_LOGINS] so the rows look realistic rather than identical. Each user
     * is an existing, active fixture user, so seeding only appends events. The admin is just another user
     * here: it has a consumption row and can hold cups, purchases, and deposits like anyone.
     *
     * @param admin the resolved fixture admin, acting for the admin-only deposits
     */
    private fun seedFixtureUserHistories(admin: User) {
        ENRICHED_FIXTURE_LOGINS.forEachIndexed { index, login ->
            seedVariedHistory(userService.getByLoginName(login), index, admin)
        }
    }

    /**
     * Seeds a varied coffee, own-bean-purchase, and deposit history onto an existing, active [user].
     * The volume varies with [index] so consecutive users do not get identical rows: a handful of cups, one
     * to three own purchases, and one or two deposits, all attributed to the user (the deposits acted by
     * the [admin]). User purchases are 100% private, and deposits only add to the kitty, so this never
     * draws the kitty down.
     *
     * @param user the existing active user to enrich
     * @param index  the user's position, used to vary the counts and amounts
     * @param admin  the resolved fixture admin, acting for the admin-only deposits
     */
    private fun seedVariedHistory(
        user: User,
        index: Int,
        admin: User
    ) {
        repeat(BASE_COFFEES + index * COFFEE_STEP) {
            coffeeConsumptionService.applyDelta(user.persistedId, 1, user)
        }
        repeat(1 + index % MAX_EXTRA_OWN_EXPENSES) { purchase ->
            val weightGrams = BASE_EXPENSE_GRAMS + (index + purchase) * EXPENSE_GRAMS_STEP
            val amountCents = BASE_EXPENSE_CENTS + (index + purchase) * EXPENSE_CENTS_STEP
            expenseService.recordOwn(weightGrams, amountCents, "demo bean purchase", user)
        }
        repeat(1 + index % MAX_EXTRA_DEPOSITS) { deposit ->
            val amountCents = BASE_DEPOSIT_CENTS + (index + deposit) * DEPOSIT_CENTS_STEP
            paymentService.recordDeposit(user.persistedId, amountCents, "demo deposit", admin)
        }
    }

    /**
     * Creates one demo user directly through the service's upsert (the same seeding path the fixtures use,
     * which bypasses the admin-only `create` check), then creates their consumption at zero so the count can
     * be advanced. Returns the persisted user (with their assigned id and capability token).
     */
    private fun createUser(spec: DemoUser): User {
        val created =
            userService.upsert(
                User(
                    loginName = spec.loginName,
                    emailAddress = "${spec.loginName}@se.uni-heidelberg.de",
                    firstName = spec.firstName,
                    lastName = spec.lastName,
                    role = spec.role,
                    // always created active so the consumption/expense seeding succeeds; a spec marked
                    // inactive is deactivated by the caller after its history is seeded
                    active = true,
                    // an admin needs a password; a user authenticates by their capability token alone
                    password = if (spec.role == Role.ADMIN) DEMO_ADMIN_PASSWORD else null
                )
            )
        coffeeConsumptionService.createForUser(created)
        return created
    }

    /**
     * Layers a representative history onto the primary demo capability link, the fixture user
     * [PRIMARY_DEMO_LOGIN] (the one printed on the wall for demos), so its unified activity has every entry kind
     * (cups, own bean purchases, and a deposit) and the "All"/"Cups"/"Expenses"/"Deposits" activity tabs each
     * show data. The fixture loader already created this user (active, with a consumption row at zero), so
     * this only adds events; the user stays active. Reuses the same service calls the demo users use.
     *
     * @param admin the resolved fixture admin, acting for the admin-only deposit
     */
    private fun seedPrimaryDemoUserHistory(admin: User) {
        val user = userService.getByLoginName(PRIMARY_DEMO_LOGIN)
        repeat(PRIMARY_DEMO_COFFEES) {
            coffeeConsumptionService.applyDelta(user.persistedId, 1, user)
        }
        PRIMARY_DEMO_OWN_EXPENSES.forEach { (weightGrams, amountCents) ->
            expenseService.recordOwn(weightGrams, amountCents, "demo bean purchase", user)
        }
        paymentService.recordDeposit(user.persistedId, PRIMARY_DEMO_DEPOSIT_CENTS, "demo deposit", admin)
        log.info { "Seeded the primary demo user ($PRIMARY_DEMO_LOGIN) with a full demo activity." }
    }

    /**
     * Records the three admin bean-purchase variants for the primary demo user [PRIMARY_DEMO_LOGIN], so the
     * dev app demonstrates every expense split type the admin can record: a **private-only** purchase (the
     * whole total credited to the user, nothing from the kitty), a **kitty-only** purchase (the whole total
     * drawn from the kitty, nothing credited to the user), and a **private+kitty split** (a portion each).
     * Each shows up on the admin expense log, and the kitty-touching ones on the kitty history too.
     *
     * The buyer is the user; the actor is the [admin] (only an admin may record these). The kitty portions
     * (the kitty-only total and the split's kitty portion) draw the kitty down, so the caller must invoke this
     * only after the kitty float and all deposits are seeded; with the [KITTY_FLOAT_CENTS] float plus the
     * demo deposits available, these small draws keep the kitty non-negative.
     *
     * @param admin the resolved fixture admin, acting for the admin-only records
     */
    private fun seedPrimaryDemoUserAdminExpenses(admin: User) {
        val user = userService.getByLoginName(PRIMARY_DEMO_LOGIN)
        ADMIN_EXPENSE_VARIANTS.forEach { variant ->
            expenseService.record(
                buyerUserId = user.persistedId,
                weightGrams = variant.weightGrams,
                amountCents = variant.totalCents,
                privateAmountCents = variant.privateCents,
                kittyAmountCents = variant.kittyCents,
                note = variant.note,
                actingUser = admin
            )
            log.info {
                "Seeded an admin ${variant.label} expense for the primary demo user ($PRIMARY_DEMO_LOGIN): " +
                    "${variant.totalCents}c total = ${variant.privateCents}c private + ${variant.kittyCents}c kitty."
            }
        }
    }

    /**
     * Raises the global price once and applies an admin absolute count correction to the primary demo user,
     * so the admin global activity feed has a `PRICE_CHANGE` row and an admin-override consumption row (the two
     * activity kinds the per-user and kitty seeds never produce). Both are recorded by the system actor
     * (this runs outside a web request), so they sit on top of the existing seeded history.
     *
     * @param admin the resolved fixture admin, acting for the admin-only price change and count correction
     */
    private fun seedPriceChangeAndCorrection(admin: User) {
        coffeePriceService.setPrice(DEMO_NEW_PRICE_CENTS, admin)
        val user = userService.getByLoginName(PRIMARY_DEMO_LOGIN)
        coffeeConsumptionService.setTotal(
            user.persistedId,
            PRIMARY_DEMO_COFFEES + DEMO_CORRECTION_DELTA,
            "demo admin count correction",
            admin
        )
        log.info {
            "Seeded a price change (to ${DEMO_NEW_PRICE_CENTS}c) and an admin count correction " +
                "(+$DEMO_CORRECTION_DELTA on $PRIMARY_DEMO_LOGIN) for the global activity feed."
        }
    }

    private companion object {
        // runs after the fixture reset+seed (200) and the price seed (250), so a price is in effect before
        // any demo coffee is added and the demo users layer on top of the seeded fixtures
        private const val ORDER = 260

        // a one-off price change and an admin count correction, so the admin global activity feed exercises
        // the PRICE_CHANGE row and the admin-override consumption row on a fresh dev start
        private const val DEMO_NEW_PRICE_CENTS = 60
        private const val DEMO_CORRECTION_DELTA = 2

        // the seeded fixture admin, resolved to satisfy the admin-only deposit and kitty operations
        private const val ADMIN_LOGIN = "jane_doe"

        // a demo password for the extra admin users (dev only; an admin requires a password). Meets the
        // admin password policy: >= 24 chars, with lower/upper/digit.
        private const val DEMO_ADMIN_PASSWORD = "demoAdminPasswordSecure42"

        // an initial kitty float (euro cents) so the kitty history is non-empty on a fresh dev start
        private const val KITTY_FLOAT_CENTS = 5_000

        // the fixture user whose capability link is the primary demo link (printed on the wall for demos);
        // given a full demo activity below so every activity tab shows data. Kept active.
        private const val PRIMARY_DEMO_LOGIN = "maxmustermann"
        private const val PRIMARY_DEMO_COFFEES = 8
        private val PRIMARY_DEMO_OWN_EXPENSES = listOf(500 to 1299, 1000 to 2499)
        private const val PRIMARY_DEMO_DEPOSIT_CENTS = 2000

        // the three admin-recorded bean-purchase variants for the primary demo user, one per split type, so
        // the admin expense log shows every variant. Each satisfies private + kitty == total. The kitty
        // portions (the kitty-only total of 1000c and the split's 600c = 1600c drawn) are seeded after the
        // kitty float and all deposits; the float (KITTY_FLOAT_CENTS) plus deposits cover these small
        // draws, so the kitty stays >= 0.
        private val ADMIN_EXPENSE_VARIANTS =
            listOf(
                // private-only: the whole total credited to the user, nothing from the kitty
                AdminExpenseVariant(
                    label = "private-only",
                    weightGrams = 500,
                    totalCents = 800,
                    privateCents = 800,
                    kittyCents = 0,
                    note = "demo private-only bean purchase"
                ),
                // kitty-only: the whole total drawn from the kitty, nothing credited to the user
                AdminExpenseVariant(
                    label = "kitty-only",
                    weightGrams = 750,
                    totalCents = 1_000,
                    privateCents = 0,
                    kittyCents = 1_000,
                    note = "demo kitty-only bean purchase"
                ),
                // private+kitty split: a portion credited to the user, a portion drawn from the kitty
                AdminExpenseVariant(
                    label = "split",
                    weightGrams = 1_000,
                    totalCents = 1_500,
                    privateCents = 900,
                    kittyCents = 600,
                    note = "demo split bean purchase"
                )
            )

        // the remaining existing fixture users (the admin and the fixture users other than the primary
        // demo user) given a rich, varied history so almost every user has populated activity/history views.
        // The admin is just another user here: it has a consumption row and can hold cups, purchases, and
        // deposits like anyone.
        private val ENRICHED_FIXTURE_LOGINS = listOf(ADMIN_LOGIN, "student2023", "lisa_lee", "olivia_lee")

        // knobs for the varied per-user history; the volume scales with the user's index so consecutive
        // users do not get identical rows
        private const val BASE_COFFEES = 4
        private const val COFFEE_STEP = 3
        private const val MAX_EXTRA_OWN_EXPENSES = 3
        private const val BASE_EXPENSE_GRAMS = 250
        private const val EXPENSE_GRAMS_STEP = 250
        private const val BASE_EXPENSE_CENTS = 899
        private const val EXPENSE_CENTS_STEP = 400
        private const val MAX_EXTRA_DEPOSITS = 2
        private const val BASE_DEPOSIT_CENTS = 1000
        private const val DEPOSIT_CENTS_STEP = 500

        // a freshly created active user with no history at all, to demo the empty activity/change-log state
        private const val EMPTY_DEMO_LOGIN = "new_user"
        private val EMPTY_DEMO_USER =
            DemoUser(
                EMPTY_DEMO_LOGIN,
                "Nina",
                "Neumann",
                Role.USER,
                active = true,
                coffees = 0,
                ownExpenses = emptyList(),
                depositCents = null
            )

        private val log = KotlinLogging.logger {}

        // nine extra users with German names: a mix of roles (two admins) and active states (two
        // inactive), most with a little consumption, purchase, and deposit history so the lists paginate
        // (5/page) and the activity/history views are not empty; one (`hannes_schulz`) is deliberately left
        // empty to demo the empty state
        private val DEMO_USERS =
            listOf(
                DemoUser(
                    "anna_schneider",
                    "Anna",
                    "Schneider",
                    Role.USER,
                    true,
                    coffees = 7,
                    ownExpenses = listOf(500 to 1299),
                    depositCents = 1000
                ),
                DemoUser(
                    "bernd_fischer",
                    "Bernd",
                    "Fischer",
                    Role.USER,
                    true,
                    coffees = 3,
                    ownExpenses = emptyList(),
                    depositCents = null
                ),
                DemoUser(
                    "clara_weber",
                    "Clara",
                    "Weber",
                    Role.ADMIN,
                    true,
                    coffees = 12,
                    ownExpenses = listOf(1000 to 2499, 250 to 799),
                    depositCents = 2000
                ),
                DemoUser(
                    "david_meyer",
                    "David",
                    "Meyer",
                    Role.USER,
                    true,
                    coffees = 5,
                    ownExpenses = listOf(500 to 1199),
                    depositCents = null
                ),
                DemoUser(
                    "emma_wagner",
                    "Emma",
                    "Wagner",
                    Role.USER,
                    false,
                    coffees = 2,
                    ownExpenses = emptyList(),
                    depositCents = null
                ),
                DemoUser(
                    "felix_becker",
                    "Felix",
                    "Becker",
                    Role.USER,
                    true,
                    coffees = 9,
                    ownExpenses = listOf(750 to 1899),
                    depositCents = 1500
                ),
                DemoUser(
                    "greta_hoffmann",
                    "Greta",
                    "Hoffmann",
                    Role.ADMIN,
                    true,
                    coffees = 4,
                    ownExpenses = emptyList(),
                    depositCents = null
                ),
                // deliberately left empty (no coffees, purchases, or deposit) to demo the empty state on
                // an inactive user, alongside the active empty user `new_user`
                DemoUser(
                    "hannes_schulz",
                    "Hannes",
                    "Schulz",
                    Role.USER,
                    false,
                    coffees = 0,
                    ownExpenses = emptyList(),
                    depositCents = null
                ),
                DemoUser(
                    "ida_koch",
                    "Ida",
                    "Koch",
                    Role.USER,
                    true,
                    coffees = 6,
                    ownExpenses = listOf(500 to 1349),
                    depositCents = 800
                )
            )
    }
}
