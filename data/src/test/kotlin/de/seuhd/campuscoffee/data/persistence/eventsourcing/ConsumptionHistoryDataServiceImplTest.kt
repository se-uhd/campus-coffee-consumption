package de.seuhd.campuscoffee.data.persistence.eventsourcing

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.LocalDateTime
import java.util.UUID

/**
 * Unit tests for the consumption history's delta computation and metadata handling, with a mocked event
 * repository so the event rows can be crafted directly.
 */
class ConsumptionHistoryDataServiceImplTest {
    private val eventRepository = mock<EventRepository>()
    private val service = ConsumptionHistoryDataServiceImpl(eventRepository)

    private fun event(
        count: Int?,
        createdBy: String?,
        note: String? = null
    ): EventEntity =
        EventEntity().apply {
            createdAt = LocalDateTime.now()
            this.createdBy = createdBy
            this.note = note
            body = if (count == null) emptyMap() else mapOf("count" to count)
        }

    @Test
    fun `a zero limit returns no changes without querying the log`() {
        assertThat(service.changes(UUID.randomUUID(), 0, 0)).isEmpty()
    }

    @Test
    fun `each change's delta is the difference to the previous event, oldest first uses its own count`() {
        val id = UUID.randomUUID()
        // newest first: counts 2, 1, 0; the helper fetches limit+1 so the page can see the predecessor
        whenever(eventRepository.findHistory(any(), eq(id.toString()), any(), any()))
            .thenReturn(listOf(event(2, "max"), event(1, "max"), event(0, "system")))

        val changes = service.changes(id, 2, 0)

        assertThat(changes.map { it.count }).containsExactly(2, 1)
        assertThat(changes.map { it.delta }).containsExactly(1, 1)
        assertThat(changes.first().createdBy).isEqualTo("max")
    }

    @Test
    fun `the limit is capped and a negative offset is treated as zero`() {
        val id = UUID.randomUUID()
        whenever(eventRepository.findHistory(any(), any(), any(), any())).thenReturn(listOf(event(1, "max")))

        service.changes(id, 500, -5)

        // limit 500 -> 100 (the page is fetched with one extra), negative offset -> 0
        verify(eventRepository).findHistory(any(), eq(id.toString()), eq(101), eq(0))
    }

    @Test
    fun `a missing count is read as zero and a missing actor falls back to system`() {
        val id = UUID.randomUUID()
        whenever(eventRepository.findHistory(any(), any(), any(), any()))
            .thenReturn(listOf(event(count = null, createdBy = null, note = "a note")))

        val changes = service.changes(id, 5, 0)

        assertThat(changes).singleElement().satisfies({
            assertThat(it.count).isEqualTo(0)
            assertThat(it.delta).isEqualTo(0)
            assertThat(it.createdBy).isEqualTo("system")
            assertThat(it.note).isEqualTo("a note")
        })
    }
}
