package de.seuhd.campuscoffee.data.migration

import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager
import java.util.UUID

/**
 * Migration-replay test for V8 (`member_balance` -> `user_balance`). The rest of the suite only ever applies
 * the migrations to an empty database, so a migration's effect on EXISTING rows (the production path) is
 * otherwise unexercised. This drives Flyway to the pre-rename version, seeds rows the way a populated
 * production database would hold them, applies the rename, and asserts the data survived. It also stands as
 * the pattern for any future data migration: migrate to a target version, seed via JDBC, migrate forward,
 * then assert.
 */
class UserBalanceRenameMigrationTest {
    @Test
    fun `V8 renames member_balance to user_balance and keeps the existing rows`() {
        PostgreSQLContainer<Nothing>(DockerImageName.parse("postgres:18-alpine")).use { db ->
            db.start()
            val url = db.jdbcUrl
            val user = db.username
            val password = db.password

            // Schema through V7 only: member_balance + kitty_balance exist, the rename has not run yet.
            Flyway
                .configure()
                .dataSource(url, user, password)
                .locations(MIGRATIONS)
                .target(MigrationVersion.fromVersion("7"))
                .load()
                .migrate()

            val userId = UUID.randomUUID()
            DriverManager.getConnection(url, user, password).use { conn ->
                conn.createStatement().use { st ->
                    st.execute(
                        """
                        INSERT INTO users (id, created_at, updated_at, login_name, email_address,
                                           first_name, last_name, role, active, capability_token)
                        VALUES ('$userId', now(), now(), 'maxmustermann', 'max@example.com',
                                'Max', 'Mustermann', 'USER', true, 'token-max')
                        """.trimIndent()
                    )
                    st.execute("INSERT INTO member_balance (user_id, balance_cents) VALUES ('$userId', $USER_BALANCE)")
                    st.execute("INSERT INTO kitty_balance (id, balance_cents) VALUES (1, $KITTY_BALANCE)")
                }
            }

            // Apply the remaining migration, which is V8: the rename.
            Flyway
                .configure()
                .dataSource(url, user, password)
                .locations(MIGRATIONS)
                .load()
                .migrate()

            DriverManager.getConnection(url, user, password).use { conn ->
                conn.createStatement().use { st ->
                    st.executeQuery("SELECT balance_cents FROM user_balance WHERE user_id = '$userId'").use { rs ->
                        assertThat(rs.next()).isTrue()
                        assertThat(rs.getLong("balance_cents")).isEqualTo(USER_BALANCE)
                    }
                    // the old table no longer exists under its old name
                    st.executeQuery("SELECT to_regclass('member_balance') AS renamed_away").use { rs ->
                        rs.next()
                        assertThat(rs.getString("renamed_away")).isNull()
                    }
                    // the unrelated kitty projection is untouched by the rename
                    st.executeQuery("SELECT balance_cents FROM kitty_balance WHERE id = 1").use { rs ->
                        assertThat(rs.next()).isTrue()
                        assertThat(rs.getLong("balance_cents")).isEqualTo(KITTY_BALANCE)
                    }
                }
            }
        }
    }

    private companion object {
        const val MIGRATIONS = "classpath:db/migration"
        const val USER_BALANCE = -1234L
        const val KITTY_BALANCE = 5000L
    }
}
