package de.seuhd.campuscoffee.api.capability

import de.seuhd.campuscoffee.domain.model.User
import de.seuhd.campuscoffee.domain.ports.QrCodeGenerator
import org.slf4j.LoggerFactory
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Renders members' capability QR codes as HTTP responses: a single high-resolution PNG for one member, or a
 * ZIP archive of every member's PNG. Each QR encodes the member's capability URL and is named after their
 * login name (`<loginName>.png`). Shared by the member's own profile endpoint and the admin's per-member and
 * bulk endpoints.
 */
@Component
class CapabilityQrResponder(
    private val capabilityUrlFactory: CapabilityUrlFactory,
    private val qrCodeGenerator: QrCodeGenerator
) {
    /**
     * Builds the PNG QR-code response for [user]'s capability URL, downloaded as `<loginName>.png`.
     *
     * @param user the member whose capability URL to encode
     * @return the PNG image with the `image/png` content type and a `<loginName>.png` download filename
     */
    fun qrResponse(user: User): ResponseEntity<ByteArray> =
        ResponseEntity
            .ok()
            .contentType(MediaType.IMAGE_PNG)
            .header(HttpHeaders.CONTENT_DISPOSITION, attachment("${safeName(user.loginName)}.png"))
            .body(pngFor(user))

    /**
     * Builds a streaming ZIP archive of the capability QR codes of all [users] that have a token, one PNG
     * entry per member named `<loginName>.png`, downloaded as `coffee-qr-codes.zip`. The archive is written
     * straight to the response stream (each entry flushed in turn) rather than buffered in memory, and at
     * most [MAX_MEMBERS] members are bundled — beyond that the cap is bundled and a warning is logged, so an
     * unexpectedly large member set cannot exhaust memory.
     *
     * @param users the members whose QR codes to bundle
     * @return the ZIP archive with the `application/zip` content type and a download filename, streamed
     */
    fun zipResponse(users: List<User>): ResponseEntity<StreamingResponseBody> {
        val withToken = users.filter { it.capabilityToken != null }
        val bundled =
            if (withToken.size > MAX_MEMBERS) {
                log.warn(
                    "Capping the QR ZIP at {} members (requested {}); bundling only the first {}.",
                    MAX_MEMBERS,
                    withToken.size,
                    MAX_MEMBERS
                )
                withToken.take(MAX_MEMBERS)
            } else {
                withToken
            }
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

    /** Encodes [user]'s capability URL into a PNG QR code; requires the member to have a capability token. */
    private fun pngFor(user: User): ByteArray {
        val token = requireNotNull(user.capabilityToken) { "User '${user.loginName}' has no capability token." }
        return qrCodeGenerator.pngQrCode(capabilityUrlFactory.urlFor(token), PNG_SIZE_PX)
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
    private fun safeName(loginName: String): String = UNSAFE_NAME_CHARS.replace(loginName, "_").ifEmpty { "member" }

    private companion object {
        private val log = LoggerFactory.getLogger(CapabilityQrResponder::class.java)
        private const val PNG_SIZE_PX = 1024
        private const val MAX_MEMBERS = 1000

        /** Every character outside the safe filename whitelist `[A-Za-z0-9_-]`. */
        private val UNSAFE_NAME_CHARS = Regex("[^A-Za-z0-9_-]")
    }
}
