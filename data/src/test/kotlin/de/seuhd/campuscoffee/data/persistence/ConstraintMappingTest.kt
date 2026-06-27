package de.seuhd.campuscoffee.data.persistence

import org.assertj.core.api.Assertions.assertThat
import org.hibernate.exception.ConstraintViolationException
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.dao.DataIntegrityViolationException

/**
 * Unit tests for [ConstraintMapping.constraintNameOf], which reads the violated constraint name from the
 * data integrity violation's Hibernate cause rather than matching on database-specific message text.
 */
class ConstraintMappingTest {
    @Test
    fun `constraintNameOf reads the constraint name from the Hibernate cause`() {
        val reportedName = "some_unique_constraint"
        val hibernateViolation = mock<ConstraintViolationException>()
        whenever(hibernateViolation.constraintName).thenReturn(reportedName)
        val exception = DataIntegrityViolationException("could not execute statement", hibernateViolation)

        assertThat(ConstraintMapping.constraintNameOf(exception)).isEqualTo(reportedName)
    }

    @Test
    fun `constraintNameOf returns null when the cause chain has no Hibernate violation`() {
        val exception =
            DataIntegrityViolationException(
                "could not execute statement",
                RuntimeException("some unrelated database error")
            )

        assertThat(ConstraintMapping.constraintNameOf(exception)).isNull()
    }
}
