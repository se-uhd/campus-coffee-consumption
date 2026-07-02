package de.seuhd.campuscoffee.domain.implementation

import de.seuhd.campuscoffee.domain.exceptions.ForbiddenException
import de.seuhd.campuscoffee.domain.exceptions.ValidationException
import de.seuhd.campuscoffee.domain.model.CoffeeBean
import de.seuhd.campuscoffee.domain.model.CoffeeBeanRatings
import de.seuhd.campuscoffee.domain.model.Role
import de.seuhd.campuscoffee.domain.model.User
import de.seuhd.campuscoffee.domain.model.persistedId
import de.seuhd.campuscoffee.domain.ports.api.CoffeeBeanService
import de.seuhd.campuscoffee.domain.ports.data.CoffeeBeanDataService
import de.seuhd.campuscoffee.domain.ports.data.CoffeeRatingDataService
import de.seuhd.campuscoffee.domain.ports.data.ExpenseDataService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Domain implementation of [CoffeeBeanService]. Reads the selectable catalog and resolves-or-creates a bean
 * by name for the expense and rating paths; renaming and merging are admin-only. A merge tombstones the
 * source bean (clears [CoffeeBean.active], sets [CoffeeBean.mergedIntoId]) rather than repointing every
 * referencing row, so the log records one event and the ratings resolve tombstones to the canonical target.
 */
@Service
class CoffeeBeanServiceImpl(
    private val coffeeBeanDataService: CoffeeBeanDataService,
    private val coffeeRatingDataService: CoffeeRatingDataService,
    private val expenseDataService: ExpenseDataService
) : CoffeeBeanService {
    override fun listSelectable(): List<CoffeeBean> =
        coffeeBeanDataService
            .getAll()
            .filter { it.active && it.mergedIntoId == null }
            .sortedBy { it.name.lowercase() }

    @Transactional
    override fun resolveOrCreate(rawName: String): CoffeeBean {
        val name = normalizeName(rawName)
        // Check-then-create: if two purchases name the identical brand-new bean in the same instant, both see
        // no match and one insert loses the unique-name index, surfacing as a DuplicationException (409). That
        // failure is safe: it rolls the whole write back atomically (no orphaned event or row) and the caller
        // can retry, at which point the bean now exists and resolves. We deliberately do not resolve the race
        // in code: the only correct fix commits the bean's creation event in a separate transaction from the
        // expense that names it, which would break this app's one-transaction-per-request event-sourcing model
        // for a collision that needs two people to first-record the same new bean name within one millisecond.
        return coffeeBeanDataService.findActiveByName(name)
            ?: coffeeBeanDataService.upsert(CoffeeBean(name = name))
    }

    @Transactional
    override fun rename(
        beanId: UUID,
        newName: String,
        actingUser: User
    ): CoffeeBean {
        requireAdmin(actingUser)
        val name = normalizeName(newName)
        val existing = coffeeBeanDataService.getById(beanId)
        return coffeeBeanDataService.upsert(existing.copy(name = name))
    }

    @Transactional
    override fun merge(
        beanId: UUID,
        targetBeanId: UUID,
        actingUser: User
    ): CoffeeBean {
        requireAdmin(actingUser)
        if (beanId == targetBeanId) {
            throw ValidationException("A bean cannot be merged into itself.")
        }
        val target = coffeeBeanDataService.getById(targetBeanId)
        if (!target.active || target.mergedIntoId != null) {
            throw ValidationException("A bean can only be merged into a live, un-merged bean.")
        }
        val source = coffeeBeanDataService.getById(beanId)
        return coffeeBeanDataService.upsert(source.copy(active = false, mergedIntoId = targetBeanId))
    }

    override fun getById(beanId: UUID): CoffeeBean = coffeeBeanDataService.getById(beanId)

    override fun mostRecentlyPurchased(): CoffeeBean? = coffeeBeanDataService.findMostRecentlyPurchased()

    override fun ratings(): List<CoffeeBeanRatings> {
        val beans = coffeeBeanDataService.getAll()
        val byId = beans.associateBy { it.persistedId }
        // resolve a bean id through any merge tombstones to its canonical bean id (bounded by the bean count
        // so a stray cycle cannot loop forever)
        val canonicalId = HashMap<UUID, UUID>()

        fun canonical(startId: UUID): UUID =
            canonicalId.getOrPut(startId) {
                var current = startId
                repeat(beans.size + 1) {
                    val mergedInto = byId[current]?.mergedIntoId ?: return@getOrPut current
                    current = mergedInto
                }
                current
            }
        val ratingsByBean = coffeeRatingDataService.getAll().groupBy { canonical(it.bean.persistedId) }
        val purchasesByBean =
            expenseDataService
                .getAll()
                .mapNotNull { expense -> expense.bean?.let { canonical(it.persistedId) to expense } }
                .groupBy({ it.first }, { it.second })
        return beans
            .filter { it.mergedIntoId == null }
            .map { bean ->
                val votes = ratingsByBean[bean.persistedId].orEmpty()
                CoffeeBeanRatings(
                    bean = bean,
                    averageValue = votes.map { it.value }.average().takeIf { votes.isNotEmpty() },
                    voteCount = votes.size,
                    latestRatingAt = votes.mapNotNull { it.createdAt }.maxOrNull(),
                    latestPurchaseAt =
                        purchasesByBean[bean.persistedId]
                            .orEmpty()
                            .mapNotNull { it.createdAt }
                            .maxOrNull()
                )
            }.sortedWith(
                compareByDescending<CoffeeBeanRatings> { it.averageValue ?: Double.NEGATIVE_INFINITY }
                    .thenByDescending { it.voteCount }
                    .thenBy { it.bean.name.lowercase() }
            )
    }

    override fun clear() = coffeeBeanDataService.clear()

    /** Trims the name and collapses inner whitespace, rejecting a name that is blank once normalized. */
    private fun normalizeName(rawName: String): String {
        val name = rawName.trim().replace(WHITESPACE, " ")
        if (name.isEmpty()) {
            throw ValidationException("A bean name cannot be blank.")
        }
        return name
    }

    /** Requires [actingUser] to be an admin (renaming and merging are admin-only), else 403. */
    private fun requireAdmin(actingUser: User) {
        if (actingUser.role != Role.ADMIN) {
            throw ForbiddenException("Only an admin may rename or merge beans.")
        }
    }

    private companion object {
        private val WHITESPACE = Regex("\\s+")
    }
}
