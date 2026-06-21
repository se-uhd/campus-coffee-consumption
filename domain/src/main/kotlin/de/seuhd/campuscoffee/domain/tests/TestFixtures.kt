package de.seuhd.campuscoffee.domain.tests

import de.seuhd.campuscoffee.domain.model.CoffeeConsumption
import de.seuhd.campuscoffee.domain.model.CoffeePrice
import de.seuhd.campuscoffee.domain.model.Role
import de.seuhd.campuscoffee.domain.model.User
import de.seuhd.campuscoffee.domain.ports.api.CoffeeConsumptionService
import de.seuhd.campuscoffee.domain.ports.api.CoffeePriceService
import de.seuhd.campuscoffee.domain.ports.api.UserService
import java.time.LocalDateTime
import java.util.UUID

/**
 * Test and demo fixtures for the consumption domain: one admin and four members, each with a deterministic
 * capability token (for repeatable demos) and a coffee consumption starting at zero. Lives in `src/main`
 * so the dev endpoint, the startup loader, and the system tests share a single source of fixture data.
 */
object TestFixtures {
    private val DATE_TIME: LocalDateTime = LocalDateTime.of(2025, 10, 29, 12, 0, 0)

    /** The initial coffee price the fixtures seed, in euro cents (50 cents per cup). */
    const val INITIAL_PRICE_CENTS = 50

    /**
     * Builds a deterministic UUID for a fixture. UUID(long mostSigBits, long leastSigBits) is the JDK
     * constructor (the two args are the high and low 64 bits), so fixtureId(1) is
     * 00000000-0000-0000-0000-000000000001. The *ForInsertion() helpers strip these before seeding, so
     * they only matter to the unit tests that read them back (e.g. to tell one fixture user from another).
     */
    private fun fixtureId(value: Long): UUID = UUID(0L, value)

    private val USER_LIST =
        listOf(
            User(
                id = fixtureId(1),
                createdAt = DATE_TIME,
                updatedAt = DATE_TIME,
                loginName = "jane_doe",
                emailAddress = "jane.doe@se.uni-heidelberg.de",
                firstName = "Jane",
                lastName = "Doe",
                role = Role.ADMIN,
                active = true,
                capabilityToken = "Rh7tK2pXmQ9vL4nB8cD1eF6gH3jZ0sW5yAuToN2kEac",
                password = "aaaMbnPdFYDqkOpS3fVA"
            ),
            User(
                id = fixtureId(2),
                createdAt = DATE_TIME,
                updatedAt = DATE_TIME,
                loginName = "maxmustermann",
                emailAddress = "max.mustermann@se.uni-heidelberg.de",
                firstName = "Max",
                lastName = "Mustermann",
                role = Role.USER,
                active = true,
                capabilityToken = "Pq3wE9rT5yU1iO7pA2sD8fG4hJ6kL0zXcVbN3mM1nBqe"
            ),
            User(
                id = fixtureId(3),
                createdAt = DATE_TIME,
                updatedAt = DATE_TIME,
                loginName = "student2023",
                emailAddress = "student2023@se.uni-heidelberg.de",
                firstName = "Student",
                lastName = "Example",
                role = Role.USER,
                active = true,
                capabilityToken = "Zx1cV7bN3mA9sD5fG2hJ8kL4qW0eR6tYuIoP1lK7jHzx"
            ),
            User(
                id = fixtureId(4),
                createdAt = DATE_TIME,
                updatedAt = DATE_TIME,
                loginName = "lisa_lee",
                emailAddress = "lisa.lee@se.uni-heidelberg.de",
                firstName = "Lisa",
                lastName = "Lee",
                role = Role.USER,
                active = true,
                capabilityToken = "Lk8jH4gF6dS2aP0oI9uY7tR3eW1qZ5xCvBnM2mN8bVlk"
            ),
            User(
                id = fixtureId(5),
                createdAt = DATE_TIME,
                updatedAt = DATE_TIME,
                loginName = "olivia_lee",
                emailAddress = "olivia.lee@se.uni-heidelberg.de",
                firstName = "Olivia",
                lastName = "Lee",
                role = Role.USER,
                active = true,
                capabilityToken = "Ty6rE2wQ8aS4dF0gH6jK1lZ3xC9vB5nMqWeR7tY1uIty"
            )
        )

    /** Returns the fixture users. */
    fun getUserFixtures(): List<User> = USER_LIST

    /** A fixture member holding [Role.USER]. */
    fun member(): User = getUserFixtures().first { it.role == Role.USER }

    /** A fixture user holding [Role.ADMIN]. */
    fun admin(): User = getUserFixtures().first { it.role == Role.ADMIN }

    /**
     * The login name and raw password of the representative fixture user with [role]. Only an admin has a
     * password (a member authenticates with their capability link, not a password), so this supports
     * [Role.ADMIN] only; it is the single source of the admin credentials reused by the system tests.
     *
     * @param role the role whose representative fixture credentials to return (must be [Role.ADMIN])
     */
    fun rawCredentialsFor(role: Role): Pair<String, String> {
        require(role == Role.ADMIN) { "Only an admin has a password; a member authenticates with their link." }
        return admin().let { it.loginName to requireNotNull(it.password) }
    }

    /**
     * The raw capability token of the fixture member with the given [loginName], used by the system tests
     * to authenticate as a member via the `X-Coffee-Token` header.
     *
     * @param loginName the login name of the fixture member
     */
    fun rawCapabilityTokenFor(loginName: String): String =
        requireNotNull(getUserFixtures().first { it.loginName == loginName }.capabilityToken)

    /**
     * Returns the fixture users with their ids and timestamps stripped, ready for insertion. The
     * capability token and raw password are kept so the seeded members have stable, demo-able coffee links.
     */
    fun getUserFixturesForInsertion(): List<User> =
        getUserFixtures().map { it.copy(id = null, createdAt = null, updatedAt = null) }

    /**
     * Persists the fixture users through the given service and returns them.
     *
     * @param userService the service used to persist the users
     */
    fun createUserFixtures(userService: UserService): List<User> =
        getUserFixturesForInsertion().map { userService.upsert(it) }

    /**
     * Creates one coffee consumption per persisted user, each starting at `count = 0`.
     *
     * @param coffeeConsumptionService the service used to create the consumptions
     * @param createdUsers             the persisted users to create a consumption for
     */
    fun createConsumptionFixtures(
        coffeeConsumptionService: CoffeeConsumptionService,
        createdUsers: List<User>
    ): List<CoffeeConsumption> = createdUsers.map { coffeeConsumptionService.createForUser(it) }

    /**
     * Seeds the initial coffee price ([INITIAL_PRICE_CENTS]) if none exists yet, and returns it. Seeded
     * after the users and consumptions so it does not perturb their deterministic ids, and so a price is in
     * effect before any real coffee is added.
     *
     * @param coffeePriceService the service used to seed the price
     */
    fun createPriceFixture(coffeePriceService: CoffeePriceService): CoffeePrice =
        coffeePriceService.ensureInitialPrice(INITIAL_PRICE_CENTS)

    /**
     * Loads the users, their (zeroed) consumptions, and the initial price into the given services and
     * returns the counts (users, consumptions). Used by the dev endpoint and the optional startup loader.
     *
     * @param userService              the service used to persist the users
     * @param coffeeConsumptionService the service used to create the consumptions
     * @param coffeePriceService       the service used to seed the initial price
     */
    fun loadAll(
        userService: UserService,
        coffeeConsumptionService: CoffeeConsumptionService,
        coffeePriceService: CoffeePriceService
    ): Pair<Int, Int> {
        val users = createUserFixtures(userService)
        val consumptions = createConsumptionFixtures(coffeeConsumptionService, users)
        createPriceFixture(coffeePriceService)
        return users.size to consumptions.size
    }
}
