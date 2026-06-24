package de.seuhd.campuscoffee.tests.system

import de.seuhd.campuscoffee.api.dtos.ConsumptionDeltaDto
import de.seuhd.campuscoffee.api.dtos.ConsumptionDto
import de.seuhd.campuscoffee.api.dtos.ConsumptionOverrideDto
import de.seuhd.campuscoffee.api.dtos.UserDto
import de.seuhd.campuscoffee.domain.model.persistedId
import de.seuhd.campuscoffee.tests.SystemTestUtils.client
import de.seuhd.campuscoffee.tests.SystemTestUtils.statusCode
import de.seuhd.campuscoffee.tests.SystemTestUtils.withAdmin
import de.seuhd.campuscoffee.tests.SystemTestUtils.withMember
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.client.returnResult
import java.io.ByteArrayInputStream
import java.util.UUID
import java.util.zip.ZipInputStream

/**
 * System tests for the admin member-management and consumption-by-id flow, authenticated by a JWT minted
 * at the token endpoint with the seeded admin fixture credentials.
 */
class UserAdminSystemTests : AbstractSystemTest() {
    private fun memberId(): UUID = seededUser("maxmustermann").persistedId

    private fun adminConsumption(id: UUID): ConsumptionDto =
        client()
            .get()
            .uri("/api/users/{id}/consumption", id)
            .accept(MediaType.APPLICATION_JSON)
            .withAdmin()
            .exchange()
            .returnResult<ConsumptionDto>()
            .responseBody!!

    @Test
    fun `creating a member returns 201 with the assembled capability URL`() {
        val body =
            mapOf(
                "loginName" to "newmember",
                "emailAddress" to "new.member@se.de",
                "firstName" to "New",
                "lastName" to "Member",
                "role" to "USER",
                "password" to "a-strong-password"
            )

        val result =
            client()
                .post()
                .uri("/api/users")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .withAdmin()
                .exchange()
                .returnResult<UserDto>()

        assertThat(result.status.value()).isEqualTo(201)
        assertThat(result.responseBody!!.capabilityUrl).contains("/login/")
    }

    @Test
    fun `deleting a member returns 204 and cascades their consumption`() {
        val body =
            mapOf(
                "loginName" to "todelete",
                "emailAddress" to "to.delete@se.de",
                "firstName" to "To",
                "lastName" to "Delete",
                "role" to "USER",
                "password" to "a-strong-password"
            )
        val created =
            client()
                .post()
                .uri("/api/users")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .withAdmin()
                .exchange()
                .returnResult<UserDto>()
                .responseBody!!

        // delete succeeds (the user_id FK cascades the member's consumption row away)
        val delete =
            client()
                .delete()
                .uri("/api/users/{id}", created.persistedId)
                .withAdmin()
                .exchange()
                .returnResult<Void>()
        assertThat(delete.status.value()).isEqualTo(204)

        // the member is gone
        val afterGet =
            client()
                .get()
                .uri("/api/users/{id}", created.persistedId)
                .accept(MediaType.APPLICATION_JSON)
                .withAdmin()
                .exchange()
                .returnResult<UserDto>()
        assertThat(afterGet.status.value()).isEqualTo(404)
    }

    @Test
    fun `listing users returns the seeded members`() {
        val result =
            client()
                .get()
                .uri("/api/users")
                .accept(MediaType.APPLICATION_JSON)
                .withAdmin()
                .exchange()
                .returnResult<Array<UserDto>>()

        assertThat(result.status.value()).isEqualTo(200)
        assertThat(result.responseBody!!).hasSize(5)
    }

    @Test
    fun `getting me returns the signed-in admin's own user`() {
        val result =
            client()
                .get()
                .uri("/api/users/me")
                .accept(MediaType.APPLICATION_JSON)
                .withAdmin()
                .exchange()
                .returnResult<UserDto>()

        assertThat(result.responseBody!!.loginName).isEqualTo("jane_doe")
    }

    @Test
    fun `updating a member deactivates the account`() {
        val id = memberId()
        val body =
            mapOf(
                "id" to id.toString(),
                "loginName" to "maxmustermann",
                "emailAddress" to "max.mustermann@se.uni-heidelberg.de",
                "firstName" to "Max",
                "lastName" to "Mustermann",
                "role" to "USER",
                "active" to false
            )

        val result =
            client()
                .put()
                .uri("/api/users/{id}", id)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .withAdmin()
                .exchange()
                .returnResult<UserDto>()

        assertThat(result.status.value()).isEqualTo(200)
        assertThat(result.responseBody!!.active).isFalse()
    }

    @Test
    fun `rotating a member's link issues a new capability URL`() {
        val id = memberId()
        val before =
            client()
                .get()
                .uri("/api/users/{id}/link", id)
                .accept(MediaType.APPLICATION_JSON)
                .withAdmin()
                .exchange()
                .returnResult<UserDto>()
                .responseBody!!
                .capabilityUrl

        val after =
            client()
                .post()
                .uri("/api/users/{id}/link/rotate", id)
                .withAdmin()
                .exchange()
                .returnResult<UserDto>()
                .responseBody!!
                .capabilityUrl

        assertThat(after).isNotNull()
        assertThat(after).isNotEqualTo(before)
    }

    @Test
    fun `downloading a member's QR code returns a PNG`() {
        val result =
            client()
                .get()
                .uri("/api/users/{id}/qr.png", memberId())
                .withAdmin()
                .exchange()
                .returnResult<ByteArray>()

        assertThat(result.status.value()).isEqualTo(200)
        assertThat(result.responseHeaders.contentType.toString()).isEqualTo(MediaType.IMAGE_PNG_VALUE)
    }

    @Test
    fun `downloading all QR codes returns a zip of per-member PNGs named by login`() {
        val result =
            client()
                .get()
                .uri("/api/users/qr.zip")
                .withAdmin()
                .exchange()
                .returnResult<ByteArray>()

        assertThat(result.status.value()).isEqualTo(200)
        assertThat(result.responseHeaders.contentType.toString()).isEqualTo("application/zip")
        val entries = zipEntryNames(result.responseBody!!)
        // one PNG per seeded user (the admin and the four members), each named after the member's login
        assertThat(entries).hasSize(5)
        assertThat(entries).allMatch { it.endsWith(".png") }
        assertThat(entries).contains("jane_doe.png", "maxmustermann.png")
    }

    @Test
    fun `a member capability token downloading all QR codes returns 403 Forbidden`() {
        // the bulk QR ZIP is admin-only; a member's capability token grants only self-service
        val status =
            client()
                .get()
                .uri("/api/users/qr.zip")
                .withMember("maxmustermann")
                .exchange()
                .statusCode()
        assertThat(status).isEqualTo(403)
    }

    @Test
    fun `downloading all QR codes as a PDF returns a PDF sheet`() {
        val result =
            client()
                .get()
                .uri("/api/users/qr.pdf")
                .withAdmin()
                .exchange()
                .returnResult<ByteArray>()

        assertThat(result.status.value()).isEqualTo(200)
        assertThat(result.responseHeaders.contentType.toString()).isEqualTo(MediaType.APPLICATION_PDF_VALUE)
        assertThat(result.responseHeaders.contentDisposition.filename).isEqualTo("coffee-qr-codes.pdf")
        // a well-formed PDF starts with the %PDF magic bytes
        assertThat(result.responseBody!!.copyOfRange(0, 4).decodeToString()).isEqualTo("%PDF")
    }

    @Test
    fun `a member capability token downloading the QR PDF returns 403 Forbidden`() {
        val status =
            client()
                .get()
                .uri("/api/users/qr.pdf")
                .withMember("maxmustermann")
                .exchange()
                .statusCode()
        assertThat(status).isEqualTo(403)
    }

    @Test
    fun `a deactivated member is excluded from the bulk QR downloads`() {
        // deactivate one member; they must drop out of the ZIP (and the PDF, which shares the selection)
        val id = memberId()
        client()
            .put()
            .uri("/api/users/{id}", id)
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                mapOf(
                    "id" to id.toString(),
                    "loginName" to "maxmustermann",
                    "emailAddress" to "max.mustermann@se.uni-heidelberg.de",
                    "firstName" to "Max",
                    "lastName" to "Mustermann",
                    "role" to "USER",
                    "active" to false
                )
            ).withAdmin()
            .exchange()

        val entries =
            zipEntryNames(
                client()
                    .get()
                    .uri("/api/users/qr.zip")
                    .withAdmin()
                    .exchange()
                    .returnResult<ByteArray>()
                    .responseBody!!
            )
        // four of the five seeded members remain; the deactivated one is gone
        assertThat(entries).hasSize(4)
        assertThat(entries).doesNotContain("maxmustermann.png")

        // the PDF still serves (its rendered content is asserted in the data-layer adapter test)
        val pdfStatus =
            client()
                .get()
                .uri("/api/users/qr.pdf")
                .withAdmin()
                .exchange()
                .statusCode()
        assertThat(pdfStatus).isEqualTo(200)
    }

    @Test
    fun `an admin increments a member's count by id`() {
        val id = memberId()

        val result =
            client()
                .post()
                .uri("/api/users/{id}/consumption", id)
                .contentType(MediaType.APPLICATION_JSON)
                .body(ConsumptionDeltaDto(1))
                .withAdmin()
                .exchange()
                .returnResult<ConsumptionDto>()

        assertThat(result.responseBody!!.total).isEqualTo(1)
    }

    @Test
    fun `an admin decrements a member's count and the change log records it`() {
        val id = memberId()
        client()
            .post()
            .uri("/api/users/{id}/consumption", id)
            .contentType(MediaType.APPLICATION_JSON)
            .body(ConsumptionDeltaDto(1))
            .withAdmin()
            .exchange()
        val afterDecrement =
            client()
                .post()
                .uri("/api/users/{id}/consumption", id)
                .contentType(MediaType.APPLICATION_JSON)
                .body(ConsumptionDeltaDto(-1))
                .withAdmin()
                .exchange()
                .returnResult<ConsumptionDto>()
                .responseBody!!

        assertThat(afterDecrement.total).isEqualTo(0)
        // the change log (carried in the response) records the -1 newest first
        assertThat(afterDecrement.changes.first().delta).isEqualTo(-1)
    }

    @Test
    fun `an admin overrides a member's count and records a note`() {
        val id = memberId()

        client()
            .put()
            .uri("/api/users/{id}/consumption", id)
            .contentType(MediaType.APPLICATION_JSON)
            .body(ConsumptionOverrideDto(7, "manual correction"))
            .withAdmin()
            .exchange()

        assertThat(adminConsumption(id).total).isEqualTo(7)
        assertThat(adminConsumption(id).changes.first().note).isEqualTo("manual correction")
    }

    @Test
    fun `an admin resets a member's count to zero after payment`() {
        val id = memberId()
        client()
            .put()
            .uri("/api/users/{id}/consumption", id)
            .contentType(MediaType.APPLICATION_JSON)
            .body(ConsumptionOverrideDto(5, null))
            .withAdmin()
            .exchange()

        client()
            .put()
            .uri("/api/users/{id}/consumption", id)
            .contentType(MediaType.APPLICATION_JSON)
            .body(ConsumptionOverrideDto(0, "paid"))
            .withAdmin()
            .exchange()

        assertThat(adminConsumption(id).total).isEqualTo(0)
    }

    @Test
    fun `deactivating the last active admin returns 409 Conflict`() {
        // jane_doe is the only seeded admin, so deactivating her would leave no active admin
        val adminId = seededUser("jane_doe").persistedId
        val body =
            mapOf(
                "id" to adminId.toString(),
                "loginName" to "jane_doe",
                "emailAddress" to "jane.doe@se.uni-heidelberg.de",
                "firstName" to "Jane",
                "lastName" to "Doe",
                "role" to "ADMIN",
                "active" to false
            )

        val status =
            client()
                .put()
                .uri("/api/users/{id}", adminId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .withAdmin()
                .exchange()
                .statusCode()

        assertThat(status).isEqualTo(409)
    }

    @Test
    fun `demoting the last active admin returns 409 Conflict`() {
        val adminId = seededUser("jane_doe").persistedId
        val body =
            mapOf(
                "id" to adminId.toString(),
                "loginName" to "jane_doe",
                "emailAddress" to "jane.doe@se.uni-heidelberg.de",
                "firstName" to "Jane",
                "lastName" to "Doe",
                "role" to "USER",
                "active" to true
            )

        val status =
            client()
                .put()
                .uri("/api/users/{id}", adminId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .withAdmin()
                .exchange()
                .statusCode()

        assertThat(status).isEqualTo(409)
    }

    @Test
    fun `deleting the last active admin returns 409 Conflict`() {
        val adminId = seededUser("jane_doe").persistedId

        val status =
            client()
                .delete()
                .uri("/api/users/{id}", adminId)
                .withAdmin()
                .exchange()
                .statusCode()

        assertThat(status).isEqualTo(409)
    }

    /** The entry names contained in a ZIP archive's bytes. */
    private fun zipEntryNames(bytes: ByteArray): List<String> {
        val names = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                names += entry.name
                entry = zip.nextEntry
            }
        }
        return names
    }
}
