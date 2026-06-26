package de.seuhd.campuscoffee.domain.implementation

import de.seuhd.campuscoffee.domain.exceptions.ForbiddenException
import de.seuhd.campuscoffee.domain.model.ActivityEntry
import de.seuhd.campuscoffee.domain.model.ActivityEntryType
import de.seuhd.campuscoffee.domain.model.GlobalActivityEntry
import de.seuhd.campuscoffee.domain.model.Role
import de.seuhd.campuscoffee.domain.model.User
import de.seuhd.campuscoffee.domain.ports.data.ActivityDataService
import de.seuhd.campuscoffee.domain.ports.data.UserDataService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.LocalDateTime
import java.util.UUID

/**
 * Unit tests for ActivityServiceImpl, mocking the data ports. Covers the chronological feeds (a member's
 * activity, the kitty history, and the admin global activity) and their authorization and paging rules: a
 * member reads only their own activity; the kitty and global feeds are admin-only; the global feed enriches
 * each subject with a display name and labels a hard-deleted subject.
 */
class ActivityServiceTest {
    private val activityDataService: ActivityDataService = mock()
    private val userDataService: UserDataService = mock()

    private val service = ActivityServiceImpl(activityDataService, userDataService)

    private val memberId: UUID = UUID(0L, 1L)

    private val member =
        User(
            id = memberId,
            loginName = "max",
            emailAddress = "max@se.de",
            firstName = "Max",
            lastName = "Mustermann",
            role = Role.USER,
            active = true
        )
    private val admin =
        User(
            id = UUID(0L, 99L),
            loginName = "jane",
            emailAddress = "jane@se.de",
            firstName = "Jane",
            lastName = "Doe",
            role = Role.ADMIN,
            active = true
        )

    private fun entry(
        type: ActivityEntryType,
        amountCents: Long,
        runningBalanceCents: Long
    ) = ActivityEntry(
        type = type,
        id = UUID.fromString("00000000-0000-0000-0000-000000000001"),
        createdAt = LocalDateTime.now(),
        createdBy = "max",
        note = null,
        amountCents = amountCents,
        runningBalanceCents = runningBalanceCents
    )

    private fun globalEntry(subjectUserId: UUID?) =
        GlobalActivityEntry(
            type = ActivityEntryType.CONSUMPTION,
            id = UUID.randomUUID(),
            createdAt = LocalDateTime.now(),
            actorLogin = "SYSTEM",
            subjectUserId = subjectUserId,
            subjectLogin = subjectUserId?.let { "max" },
            subjectName = null,
            note = null,
            memberEffectCents = -50,
            memberBalanceCents = -50,
            kittyEffectCents = null,
            kittyBalanceCents = null
        )

    @Test
    fun `memberActivity returns the newest-first page for an admin`() {
        whenever(userDataService.getById(memberId)).thenReturn(member)
        whenever(activityDataService.userActivity(memberId, "max"))
            .thenReturn(
                listOf(entry(ActivityEntryType.CONSUMPTION, -50, -50), entry(ActivityEntryType.DEPOSIT, 100, 50))
            )

        val page = service.memberActivity(memberId, 10, 0, admin)

        // newest first: the deposit, then the consumption
        assertThat(page.map { it.type }).containsExactly(ActivityEntryType.DEPOSIT, ActivityEntryType.CONSUMPTION)
    }

    @Test
    fun `memberActivity by a non-owner non-admin throws ForbiddenException`() {
        val stranger = member.copy(id = UUID(0L, 7L), loginName = "other")
        whenever(userDataService.getById(memberId)).thenReturn(member)

        assertThrows<ForbiddenException> { service.memberActivity(memberId, 10, 0, stranger) }
    }

    @Test
    fun `a member's activity reflects only the private portion of a split expense and never the kitty portion`() {
        // the member activity the data service projects for a split bean purchase carries only the buyer's
        // private credit (PRIVATE_EXPENSE); the kitty-funded portion must never appear in the member view.
        val privateOnly = entry(ActivityEntryType.PRIVATE_EXPENSE, 400, 400)
        whenever(userDataService.getById(memberId)).thenReturn(member)
        whenever(activityDataService.userActivity(memberId, "max")).thenReturn(listOf(privateOnly))

        val page = service.memberActivity(memberId, 10, 0, admin)

        assertThat(page).singleElement()
        assertThat(page.first().type).isEqualTo(ActivityEntryType.PRIVATE_EXPENSE)
        assertThat(page.first().amountCents).isEqualTo(400)
        assertThat(page.map { it.type })
            .doesNotContain(ActivityEntryType.KITTY_EXPENSE, ActivityEntryType.KITTY_ADJUSTMENT)
    }

    @Test
    fun `memberActivity clamps a negative offset to zero and a limit above the cap to the maximum`() {
        val full = (1..120).map { entry(ActivityEntryType.CONSUMPTION, -50, -50L * it) }
        whenever(userDataService.getById(memberId)).thenReturn(member)
        whenever(activityDataService.userActivity(memberId, "max")).thenReturn(full)

        val page = service.memberActivity(memberId, limit = 1_000, offset = -10, actingUser = admin)

        // the limit is capped at the page maximum (100); a negative offset clamps to 0, so the page starts newest
        assertThat(page).hasSize(100)
        assertThat(page.first().runningBalanceCents).isEqualTo(-6_000L)
    }

    @Test
    fun `memberActivity returns an empty page for an offset beyond the end of the activity`() {
        val full = listOf(entry(ActivityEntryType.CONSUMPTION, -50, -50))
        whenever(userDataService.getById(memberId)).thenReturn(member)
        whenever(activityDataService.userActivity(memberId, "max")).thenReturn(full)

        assertThat(service.memberActivity(memberId, limit = 10, offset = 5, actingUser = admin)).isEmpty()
    }

    @Test
    fun `kittyHistory by a non-admin throws ForbiddenException`() {
        assertThrows<ForbiddenException> { service.kittyHistory(10, 0, member) }
    }

    @Test
    fun `kittyHistory clamps a limit above the cap to the maximum for an admin`() {
        val full = (1..150).map { entry(ActivityEntryType.KITTY_ADJUSTMENT, 100, 100L * it) }
        whenever(activityDataService.kittyHistory()).thenReturn(full)

        assertThat(service.kittyHistory(limit = 500, offset = 0, actingUser = admin)).hasSize(100)
    }

    @Test
    fun `globalActivity by a non-admin throws ForbiddenException`() {
        assertThrows<ForbiddenException> { service.globalActivity(10, 0, member) }
    }

    @Test
    fun `globalActivity enriches the subject name and labels a hard-deleted subject`() {
        val orphanId = UUID(0L, 2L)
        whenever(userDataService.getAll()).thenReturn(listOf(member))
        whenever(activityDataService.globalActivity())
            .thenReturn(listOf(globalEntry(memberId), globalEntry(orphanId)))

        val page = service.globalActivity(10, 0, admin)

        // a resolvable subject gets the member's display name; an unresolvable one (a hard-deleted member
        // whose events outlive their user row) is labeled rather than left blank or 500ing
        assertThat(page.first { it.subjectUserId == memberId }.subjectName).isEqualTo("Max Mustermann")
        assertThat(page.first { it.subjectUserId == orphanId }.subjectName).isEqualTo("(deleted member)")
    }

    @Test
    fun `globalActivityForExport by a non-admin throws ForbiddenException`() {
        assertThrows<ForbiddenException> { service.globalActivityForExport(member) }
    }

    @Test
    fun `globalActivityForExport returns the whole feed newest-first and unpaged`() {
        // more than one page worth of entries: the export must return all of them, not clamp to the page cap
        val full = (1..150).map { globalEntry(memberId) }
        whenever(userDataService.getAll()).thenReturn(listOf(member))
        whenever(activityDataService.globalActivity()).thenReturn(full)

        assertThat(service.globalActivityForExport(admin)).hasSize(150)
    }
}
