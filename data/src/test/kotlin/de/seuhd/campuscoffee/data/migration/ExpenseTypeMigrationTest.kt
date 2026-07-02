package de.seuhd.campuscoffee.data.migration

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID

/**
 * Migration-replay test for the coffee-ratings + bean-catalog schema change (`V10`/`V11`/`V12`), rehearsing
 * the exact production deploy. Production sits at `V9` with a single legacy bean-purchase expense
 * ("Heidelberger Partnerschaftskaffee", 1449 cents, 500 g, fully private) whose event body predates typing
 * (no `expenseType`/`beanId`). This drives Flyway to `V9`, seeds a row and a log event shaped exactly like
 * that production state, applies `V10`/`V11`/`V12`, and asserts:
 * - the expense is typed `BEANS` by the `V11` `DEFAULT` backfill with no bean, its other columns intact;
 * - `weight_grams` becomes nullable and the new tables exist;
 * - the append-only log is untouched (the legacy event body still carries no `expenseType`/`beanId`).
 *
 * The rest of the suite only ever migrates an empty database, so this is the one test that exercises the
 * migration against existing production-shaped data. It mirrors [UserBalanceRenameMigrationTest].
 */
class ExpenseTypeMigrationTest {
    @Test
    fun `migrating a V9 production-shaped database types the legacy expense BEANS and leaves the log immutable`() {
        PostgreSQLContainer<Nothing>(DockerImageName.parse("postgres:18-alpine")).use { db ->
            db.start()
            val url = db.jdbcUrl
            val user = db.username
            val password = db.password

            // Schema through V9 only: expenses has no expense_type/bean_id and weight_grams is NOT NULL, and
            // coffee_beans/coffee_ratings do not exist yet, exactly as production stands before this deploy.
            flyway(url, user, password).target(MigrationVersion.fromVersion("9")).load().migrate()

            val userId = UUID.randomUUID()
            val expenseId = UUID.randomUUID()
            DriverManager.getConnection(url, user, password).use { conn ->
                seedProductionShapedData(conn, userId, expenseId)
            }

            // Apply the remaining migrations: V10 (coffee_beans), V11 (expense typing), V12 (coffee_ratings).
            flyway(url, user, password).load().migrate()

            DriverManager.getConnection(url, user, password).use { conn ->
                assertExpenseTypedBeans(conn, expenseId)
                assertSchemaShape(conn)
                assertWeightCheckEnforced(conn, userId)
                assertLogUntouched(conn, expenseId)
            }
        }
    }

    /** Seeds a user, a price, the single legacy expense, and its legacy INSERT event, all as production holds them. */
    private fun seedProductionShapedData(
        conn: Connection,
        userId: UUID,
        expenseId: UUID
    ) {
        conn.createStatement().use { st ->
            st.execute(
                """
                INSERT INTO users (id, created_at, updated_at, login_name, email_address,
                                   first_name, last_name, role, active, capability_token)
                VALUES ('$userId', now(), now(), 'buyer', 'buyer@example.com',
                        'Bea', 'Buyer', 'USER', true, 'token-buyer')
                """.trimIndent()
            )
            st.execute(
                "INSERT INTO coffee_prices (id, created_at, updated_at, amount_cents, version) " +
                    "VALUES ('${UUID.randomUUID()}', now(), now(), 50, 0)"
            )
            // the legacy expense: BEANS-shaped but with no expense_type / bean_id columns to fill at V9
            st.execute(
                """
                INSERT INTO expenses (id, created_at, updated_at, buyer_user_id, weight_grams, amount_cents,
                                      private_amount_cents, kitty_amount_cents, note, version)
                VALUES ('$expenseId', now(), now(), '$userId', $WEIGHT_GRAMS, $AMOUNT_CENTS,
                        $AMOUNT_CENTS, 0, '$NOTE', 0)
                """.trimIndent()
            )
            // the matching legacy Expense INSERT event, whose body predates typing (no expenseType / beanId)
            st.execute(
                """
                INSERT INTO events (id, change_type, entity_type, entity_version, body, created_at, created_by)
                VALUES ('${UUID.randomUUID()}', 'INSERT', 'Expense', 0,
                        '{"id":"$expenseId","note":"$NOTE","createdAt":"2026-07-01T07:09:58.641911",
                          "updatedAt":"2026-07-01T07:09:58.641911","amountCents":$AMOUNT_CENTS,
                          "buyerUserId":"$userId","weightGrams":$WEIGHT_GRAMS,"kittyAmountCents":0,
                          "privateAmountCents":$AMOUNT_CENTS}'::jsonb,
                        now(), 'SYSTEM')
                """.trimIndent()
            )
        }
    }

    /** The migrated expense: typed BEANS by the DEFAULT backfill, no bean linked, all other columns preserved. */
    private fun assertExpenseTypedBeans(
        conn: Connection,
        expenseId: UUID
    ) {
        conn.createStatement().use { st ->
            st
                .executeQuery(
                    "SELECT expense_type, bean_id, weight_grams, amount_cents, private_amount_cents, note " +
                        "FROM expenses WHERE id = '$expenseId'"
                ).use { rs ->
                    assertThat(rs.next()).isTrue()
                    assertThat(rs.getString("expense_type")).isEqualTo("BEANS")
                    assertThat(rs.getObject("bean_id")).isNull()
                    assertThat(rs.getInt("weight_grams")).isEqualTo(WEIGHT_GRAMS)
                    assertThat(rs.getInt("amount_cents")).isEqualTo(AMOUNT_CENTS)
                    assertThat(rs.getInt("private_amount_cents")).isEqualTo(AMOUNT_CENTS)
                    assertThat(rs.getString("note")).isEqualTo(NOTE)
                }
        }
    }

    /** The new tables exist and weight_grams is now nullable (so a later OTHER outlay can omit it). */
    private fun assertSchemaShape(conn: Connection) {
        conn.createStatement().use { st ->
            st.executeQuery("SELECT to_regclass('coffee_beans') b, to_regclass('coffee_ratings') r").use { rs ->
                rs.next()
                assertThat(rs.getString("b")).isNotNull()
                assertThat(rs.getString("r")).isNotNull()
            }
            st
                .executeQuery(
                    "SELECT is_nullable FROM information_schema.columns " +
                        "WHERE table_name = 'expenses' AND column_name = 'weight_grams'"
                ).use { rs ->
                    rs.next()
                    assertThat(rs.getString("is_nullable")).isEqualTo("YES")
                }
        }
    }

    /**
     * The weight rule is enforced conditionally by the CHECK: an `OTHER` outlay may omit the weight, but a
     * `BEANS` purchase must still carry one (the column is nullable, the CHECK ties it to the type).
     */
    private fun assertWeightCheckEnforced(
        conn: Connection,
        userId: UUID
    ) {
        // an OTHER outlay with no bean and no weight is accepted
        conn.createStatement().use { st ->
            st.execute(
                """
                INSERT INTO expenses (id, created_at, updated_at, buyer_user_id, expense_type, weight_grams,
                                      amount_cents, private_amount_cents, kitty_amount_cents, note, version)
                VALUES ('${UUID.randomUUID()}', now(), now(), '$userId', 'OTHER', NULL, 500, 500, 0, 'filters', 0)
                """.trimIndent()
            )
        }
        // a BEANS purchase with a null weight is rejected by ck_expenses_beans_weight
        assertThatThrownBy {
            conn.createStatement().use { st ->
                st.execute(
                    """
                    INSERT INTO expenses (id, created_at, updated_at, buyer_user_id, expense_type, weight_grams,
                                          amount_cents, private_amount_cents, kitty_amount_cents, note, version)
                    VALUES ('${UUID.randomUUID()}', now(), now(), '$userId', 'BEANS', NULL, 500, 500, 0, 'beans', 0)
                    """.trimIndent()
                )
            }
        }.isInstanceOf(SQLException::class.java)
    }

    /** The append-only log is not rewritten: the legacy Expense body still carries no expenseType/beanId. */
    private fun assertLogUntouched(
        conn: Connection,
        expenseId: UUID
    ) {
        conn.createStatement().use { st ->
            st
                .executeQuery(
                    "SELECT jsonb_exists(body, 'expenseType') has_type, jsonb_exists(body, 'beanId') has_bean, " +
                        "body->>'note' note FROM events WHERE entity_type = 'Expense' AND body->>'id' = '$expenseId'"
                ).use { rs ->
                    assertThat(rs.next()).isTrue()
                    assertThat(rs.getBoolean("has_type")).isFalse()
                    assertThat(rs.getBoolean("has_bean")).isFalse()
                    assertThat(rs.getString("note")).isEqualTo(NOTE)
                }
        }
    }

    /** A Flyway builder pointed at the app's migrations and the given database. */
    private fun flyway(
        url: String,
        user: String,
        password: String
    ) = Flyway.configure().dataSource(url, user, password).locations(MIGRATIONS)

    private companion object {
        const val MIGRATIONS = "classpath:db/migration"

        // the exact production expense: "Heidelberger Partnerschaftskaffee", 1449 cents, 500 g, fully private
        const val NOTE = "Heidelberger Partnerschaftskaffee"
        const val AMOUNT_CENTS = 1449
        const val WEIGHT_GRAMS = 500
    }
}
