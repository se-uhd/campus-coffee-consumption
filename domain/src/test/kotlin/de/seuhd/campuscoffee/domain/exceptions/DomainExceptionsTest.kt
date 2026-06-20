package de.seuhd.campuscoffee.domain.exceptions

import de.seuhd.campuscoffee.domain.model.User
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Unit tests that each domain exception carries a descriptive message (and preserves its cause where it
 * takes one), so the global handler renders a meaningful body.
 */
class DomainExceptionsTest {
    private val id = UUID.randomUUID()

    @Test
    fun `NotFoundException by id and by field carry a message`() {
        assertThat(NotFoundException(User::class.java, id).message).contains("User")
        assertThat(NotFoundException(User::class.java, "loginName", "max").message).contains("loginName")
    }

    @Test
    fun `DuplicationException names the offending field`() {
        assertThat(DuplicationException(User::class.java, "loginName", "max").message).contains("loginName")
    }

    @Test
    fun `the conflict exceptions carry a message and preserve the cause`() {
        val cause = RuntimeException("boom")
        assertThat(ConcurrentUpdateException(User::class.java, id, cause).cause).isEqualTo(cause)
        assertThat(DeletionConflictException(User::class.java, id, cause).cause).isEqualTo(cause)
        assertThat(ConflictException("already zero").message).isEqualTo("already zero")
    }

    @Test
    fun `the request exceptions carry a message`() {
        assertThat(MissingFieldException(User::class.java, id, "loginName").message).contains("loginName")
        assertThat(ValidationException("bad input").message).isEqualTo("bad input")
        assertThat(ForbiddenException("not allowed").message).isEqualTo("not allowed")
    }
}
