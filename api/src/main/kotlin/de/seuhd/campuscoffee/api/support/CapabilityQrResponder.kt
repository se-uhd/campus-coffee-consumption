package de.seuhd.campuscoffee.api.support

import de.seuhd.campuscoffee.domain.model.User
import de.seuhd.campuscoffee.domain.ports.system.LabeledQrCode
import de.seuhd.campuscoffee.domain.ports.system.QrCodeService
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Renders users' capability QR codes as HTTP responses: a single high-resolution PNG for one user, a ZIP
 * archive of every active user's PNG (each named `<loginName>.png`), or a printable PDF grid of every
 * active user's code labeled with their login name. Each QR encodes the user's capability URL. Shared
 * by the user's own profile endpoint and the admin's per-user and bulk endpoints.
 */
@Component
class CapabilityQrResponder(
    private val capabilityUrlFactory: CapabilityUrlFactory,
    private val qrCodeService: QrCodeService
) {
    /**
     * Builds the PNG QR-code response for [user]'s capability URL, downloaded as `<loginName>.png`.
     *
     * @param user the user whose capability URL to encode
     * @return the PNG image with the `image/png` content type and a `<loginName>.png` download filename
     */
    fun qrResponse(user: User): ResponseEntity<ByteArray> =
        ResponseEntity
            .ok()
            .contentType(MediaType.IMAGE_PNG)
            .header(HttpHeaders.CONTENT_DISPOSITION, attachment("${safeName(user.loginName)}.png"))
            .body(pngFor(user))

    /**
     * Builds a streaming ZIP archive of the capability QR codes of all active [users] that have a token, one
     * PNG entry per user named `<loginName>.png`, downloaded as `coffee-qr-codes.zip`. The archive is
     * written straight to the response stream (each entry flushed in turn) rather than buffered in memory.
     * The eligible-user selection and the [MAX_USERS] cap are shared with the PDF export (see
     * [activeBundled]).
     *
     * @param users the users whose QR codes to bundle
     * @return the ZIP archive with the `application/zip` content type and a download filename, streamed
     */
    fun zipResponse(users: List<User>): ResponseEntity<StreamingResponseBody> {
        val bundled = activeBundled(users)
        val body =
            StreamingResponseBody { out ->
                ZipOutputStream(out).use { zip ->
                    bundled.forEach { user ->
                        zip.putNextEntry(ZipEntry("${safeName(user.loginName)}.png"))
                        zip.write(pngFor(user))
                        zip.closeEntry()
                        zip.flush()
                    }
                }
            }
        return ResponseEntity
            .ok()
            .contentType(MediaType.parseMediaType("application/zip"))
            .header(HttpHeaders.CONTENT_DISPOSITION, attachment("coffee-qr-codes.zip"))
            .body(body)
    }

    /**
     * Builds a printable PDF grid of the capability QR codes of all active [users] that have a token, each
     * code labeled with the user's login name, downloaded as `coffee-qr-codes.pdf`. The eligible-user
     * selection and the [MAX_USERS] cap are shared with the ZIP (see [activeBundled]). The codes are
     * rendered at [GRID_QR_PX], smaller than the standalone PNG but ample for a printed grid cell, which
     * keeps the document size reasonable across the whole membership.
     *
     * @param users the users whose QR codes to lay out
     * @return the PDF document with the `application/pdf` content type and a download filename
     */
    fun pdfResponse(users: List<User>): ResponseEntity<ByteArray> {
        val entries = activeBundled(users).map { LabeledQrCode(it.loginName, pngFor(it, GRID_QR_PX)) }
        return ResponseEntity
            .ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION, attachment("coffee-qr-codes.pdf"))
            .body(qrCodeService.gridPdf(entries))
    }

    /**
     * The users eligible for a bulk QR export: those that are active and have a capability token, capped at
     * [MAX_USERS] (beyond the cap the first [MAX_USERS] are taken and a warning is logged, so an
     * unexpectedly large membership cannot exhaust memory). A deactivated user is excluded: their wall code
     * is retired, so it belongs on neither the reprint sheet nor the bulk archive.
     *
     * @param users all users to select from
     * @return the active, tokened users to include, in the given order, capped at [MAX_USERS]
     */
    private fun activeBundled(users: List<User>): List<User> {
        val eligible = users.filter { it.active == true && it.capabilityToken != null }
        if (eligible.size <= MAX_USERS) {
            return eligible
        }
        log.warn {
            "Capping the bulk QR export at $MAX_USERS users (requested ${eligible.size}); " +
                "including only the first $MAX_USERS."
        }
        return eligible.take(MAX_USERS)
    }

    /** Encodes [user]'s capability URL into a PNG QR code of side [size] px; requires a capability token. */
    private fun pngFor(
        user: User,
        size: Int = PNG_SIZE_PX
    ): ByteArray {
        val token = requireNotNull(user.capabilityToken) { "User '${user.loginName}' has no capability token." }
        return qrCodeService.pngQrCode(capabilityUrlFactory.urlFor(token), size)
    }

    /** A `Content-Disposition: attachment` header value carrying the given download [filename]. */
    private fun attachment(filename: String): String =
        ContentDisposition
            .attachment()
            .filename(filename)
            .build()
            .toString()

    /**
     * Sanitizes a login name into a safe download/entry file stem: whitelist `[A-Za-z0-9_-]`, replacing every
     * other character with `_`. Defense in depth so neither the `Content-Disposition` header nor the
     * `ZipEntry` name can carry a path or header-injection character, independent of how strict the login-name
     * validation upstream happens to be. A name that sanitizes to empty falls back to `member`.
     */
    private fun safeName(loginName: String): String = UNSAFE_NAME_CHARS.replace(loginName, "_").ifEmpty { "user" }

    private companion object {
        private val log = KotlinLogging.logger {}
        private const val PNG_SIZE_PX = 1024
        private const val GRID_QR_PX = 512
        private const val MAX_USERS = 1000

        /** Every character outside the safe filename whitelist `[A-Za-z0-9_-]`. */
        private val UNSAFE_NAME_CHARS = Regex("[^A-Za-z0-9_-]")
    }
}
