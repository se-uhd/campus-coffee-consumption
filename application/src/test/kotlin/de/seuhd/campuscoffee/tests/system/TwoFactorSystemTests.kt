package de.seuhd.campuscoffee.tests.system

import com.bastiaanjansen.otp.TOTPGenerator
import de.seuhd.campuscoffee.api.dtos.TokenRequestDto
import de.seuhd.campuscoffee.api.dtos.TokenResponseDto
import de.seuhd.campuscoffee.api.dtos.TotpEnrollmentDto
import de.seuhd.campuscoffee.api.dtos.TotpStatusDto
import de.seuhd.campuscoffee.api.dtos.UserDto
import de.seuhd.campuscoffee.domain.model.Role
import de.seuhd.campuscoffee.domain.model.User
import de.seuhd.campuscoffee.domain.model.persistedId
import de.seuhd.campuscoffee.tests.SystemTestUtils.adminBearer
import de.seuhd.campuscoffee.tests.SystemTestUtils.client
import de.seuhd.campuscoffee.tests.SystemTestUtils.currentAdminTotpCode
import de.seuhd.campuscoffee.tests.SystemTestUtils.encryptCredentials
import de.seuhd.campuscoffee.tests.SystemTestUtils.statusCode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.client.returnResult

/**
 * System tests for the admin two-factor (TOTP) second factor: the enrolled-admin login contract (a valid
 * code is required and a wrong one is an identical 401), the enrollment-only session a not-yet-enrolled
 * admin receives, the enroll/activate flow, the peer reset, and the guarantee that a routine profile edit
 * does not wipe an enrolled admin's secret.
 */
class TwoFactorSystemTests : AbstractSystemTest() {
    private val adminLogin = "jane_doe"

    // Posts a login and returns the raw (status, body) result without asserting the status. Only use for a
    // success (200) path: a failure returns an ErrorResponse body that cannot be read as a TokenResponseDto.
    private fun login(
        loginName: String,
        password: String,
        totp: String? = null
    ) = client()
        .post()
        .uri("/api/auth/token")
        .contentType(MediaType.APPLICATION_JSON)
        .body(TokenRequestDto(encryptCredentials(loginName, password, totp)))
        .exchange()
        .returnResult<TokenResponseDto>()

    // Posts a login and returns only the status, without parsing the body (safe for a failure path).
    private fun loginStatus(
        loginName: String,
        password: String,
        totp: String? = null
    ): Int =
        client()
            .post()
            .uri("/api/auth/token")
            .contentType(MediaType.APPLICATION_JSON)
            .body(TokenRequestDto(encryptCredentials(loginName, password, totp)))
            .exchange()
            .statusCode()

    // Creates a second admin (with a password) who has not enrolled a second factor yet.
    private fun createUnenrolledAdmin(): User =
        userService.create(
            User(
                loginName = "new_admin",
                emailAddress = "new.admin@se.uni-heidelberg.de",
                firstName = "New",
                lastName = "Admin",
                role = Role.ADMIN,
                password = "new-admin-password-24-chars!"
            ),
            seededUser(adminLogin)
        )

    private fun statusOf(
        uri: String,
        bearer: String
    ): Int =
        client()
            .get()
            .uri(uri)
            .header(HttpHeaders.AUTHORIZATION, bearer)
            .exchange()
            .statusCode()

    @Test
    fun `an enrolled admin login without a code returns 401 Unauthorized`() {
        assertThat(loginStatus(adminLogin, "aaaMbnPdFYDqkOpS3fVA2xyz")).isEqualTo(401)
    }

    @Test
    fun `an enrolled admin login with a wrong code returns 401 Unauthorized`() {
        // derive a code guaranteed to differ from the current valid one (flipping the first digit), so this
        // never flakes on the ~1-in-a-million chance a fixed constant equals the real current code
        val current = currentAdminTotpCode()
        val wrong = (if (current[0] == '0') '1' else '0') + current.substring(1)
        assertThat(loginStatus(adminLogin, "aaaMbnPdFYDqkOpS3fVA2xyz", wrong)).isEqualTo(401)
    }

    @Test
    fun `an enrolled admin login with a valid code returns a full-scope token`() {
        val (login, password) = adminLogin to "aaaMbnPdFYDqkOpS3fVA2xyz"
        val result = login(login, password, currentAdminTotpCode())
        assertThat(result.status.value()).isEqualTo(200)
        assertThat(result.responseBody!!.enrollmentRequired).isFalse()
        // the full token reaches an admin endpoint
        assertThat(statusOf("/api/users/me", "Bearer ${result.responseBody!!.token}")).isEqualTo(200)
    }

    @Test
    fun `a not-yet-enrolled admin receives an enrollment-only token refused on admin endpoints`() {
        val (login, password) = createUnenrolledAdmin().loginName to "new-admin-password-24-chars!"
        val result = login(login, password)
        assertThat(result.status.value()).isEqualTo(200)
        assertThat(result.responseBody!!.enrollmentRequired).isTrue()
        val bearer = "Bearer ${result.responseBody!!.token}"
        // the enrollment-only token cannot reach user management (a privilege-escalation guard)
        assertThat(statusOf("/api/users", bearer)).isEqualTo(403)
        // but it can reach its own enrollment status
        assertThat(statusOf("/api/users/me/totp/status", bearer)).isEqualTo(200)
    }

    @Test
    fun `a not-yet-enrolled admin can enroll and activate to gain full access`() {
        val (login, password) = createUnenrolledAdmin().loginName to "new-admin-password-24-chars!"
        val enrollmentBearer = "Bearer ${login(login, password).responseBody!!.token}"

        // enroll: the server returns the base32 secret so this test can compute a valid code
        val enrollment =
            client()
                .post()
                .uri("/api/users/me/totp/enroll")
                .header(HttpHeaders.AUTHORIZATION, enrollmentBearer)
                .exchange()
                .returnResult<TotpEnrollmentDto>()
                .responseBody!!
        val code = TOTPGenerator.Builder(enrollment.secret).build().now()

        val activateStatus =
            client()
                .post()
                .uri("/api/users/me/totp/activate")
                .header(HttpHeaders.AUTHORIZATION, enrollmentBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .body(mapOf("code" to code))
                .exchange()
                .statusCode()
        assertThat(activateStatus).isEqualTo(204)

        // now a fresh login with a code from the enrolled secret is full-scope
        val relogin = login(login, password, TOTPGenerator.Builder(enrollment.secret).build().now())
        assertThat(relogin.responseBody!!.enrollmentRequired).isFalse()
        assertThat(statusOf("/api/users/me", "Bearer ${relogin.responseBody!!.token}")).isEqualTo(200)
    }

    @Test
    fun `an admin can reset a peer's second factor, forcing re-enrollment`() {
        val target = seededUser(adminLogin)
        // a peer reset clears the target's second factor
        val resetStatus =
            client()
                .delete()
                .uri("/api/users/{id}/totp", target.persistedId)
                .header(HttpHeaders.AUTHORIZATION, adminBearer())
                .exchange()
                .statusCode()
        assertThat(resetStatus).isEqualTo(204)

        // the target now logs in password-only into an enrollment-only session (must re-enroll)
        val result = login(adminLogin, "aaaMbnPdFYDqkOpS3fVA2xyz")
        assertThat(result.status.value()).isEqualTo(200)
        assertThat(result.responseBody!!.enrollmentRequired).isTrue()
    }

    @Test
    fun `an enrolled admin's second factor survives a routine profile edit`() {
        val admin = seededUser(adminLogin)
        // a plain admin edit (a DTO carries no TOTP fields) must not wipe the stored secret
        client()
            .put()
            .uri("/api/users/{id}", admin.persistedId)
            .header(HttpHeaders.AUTHORIZATION, adminBearer())
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                UserDto(
                    loginName = admin.loginName,
                    emailAddress = "jane.renamed@se.uni-heidelberg.de",
                    firstName = "Janet",
                    lastName = "Doe",
                    role = Role.ADMIN
                )
            ).exchange()
            .returnResult<UserDto>()

        // the second factor is still in effect: a fresh login still requires (and accepts) a code, and is
        // not downgraded to an enrollment-only session
        val result = login(adminLogin, "aaaMbnPdFYDqkOpS3fVA2xyz", currentAdminTotpCode())
        assertThat(result.status.value()).isEqualTo(200)
        assertThat(result.responseBody!!.enrollmentRequired).isFalse()
        // and the status endpoint confirms enrollment persisted
        assertThat(statusOf("/api/users/me/totp/status", adminBearer())).isEqualTo(200)
    }

    @Test
    fun `the enrollment status endpoint reports the acting admin as enrolled`() {
        val status =
            client()
                .get()
                .uri("/api/users/me/totp/status")
                .header(HttpHeaders.AUTHORIZATION, adminBearer())
                .exchange()
                .returnResult<TotpStatusDto>()
        assertThat(status.status.value()).isEqualTo(200)
        assertThat(status.responseBody!!.enrolled).isTrue()
    }
}
