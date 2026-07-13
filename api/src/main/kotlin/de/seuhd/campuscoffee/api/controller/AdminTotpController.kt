package de.seuhd.campuscoffee.api.controller

import de.seuhd.campuscoffee.api.dtos.TotpActivateRequestDto
import de.seuhd.campuscoffee.api.dtos.TotpEnrollmentDto
import de.seuhd.campuscoffee.api.dtos.TotpStatusDto
import de.seuhd.campuscoffee.api.security.CurrentUserProvider
import de.seuhd.campuscoffee.api.support.AdminSessionFactory
import de.seuhd.campuscoffee.domain.exceptions.ConflictException
import de.seuhd.campuscoffee.domain.model.User
import de.seuhd.campuscoffee.domain.ports.api.UserService
import de.seuhd.campuscoffee.domain.ports.system.QrCodeService
import de.seuhd.campuscoffee.domain.ports.system.TotpService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import java.util.UUID

/**
 * Admin two-factor (TOTP) management. The self-service enrollment endpoints live under `/users/me/totp` so a
 * pending admin (an enrollment-only `ROLE_ADMIN_ENROLLMENT` session) can reach exactly these and nothing else
 * under `/api/users`; the peer-reset endpoint is under `/users/{id}/totp`, so it stays full-`ROLE_ADMIN`-only.
 * All paths are relative to the resource; the central `/api` base is applied by ApiWebConfig.
 */
@Tag(name = "Two-factor", description = "Admin two-factor (TOTP) enrollment and reset.")
@Controller
@RequestMapping("/users")
class AdminTotpController(
    private val userService: UserService,
    private val currentUserProvider: CurrentUserProvider,
    private val totpService: TotpService,
    private val qrCodeService: QrCodeService,
    private val adminSessionFactory: AdminSessionFactory
) {
    /** Whether the acting admin has completed second-factor enrollment (so the SPA can route them). */
    @Operation(summary = "Whether the acting admin has enrolled a second factor.")
    @GetMapping("/me/totp/status")
    fun status(): ResponseEntity<TotpStatusDto> =
        ResponseEntity.ok(TotpStatusDto(userService.totpEnrolled(currentUserProvider.currentUser())))

    /**
     * Begins enrollment for the acting admin: generates and stores a pending secret and returns the base32
     * secret and `otpauth://` URI to add the account to an authenticator app (shown once). The QR image is
     * served separately by [qrCode].
     */
    @Operation(summary = "Begin two-factor enrollment; returns the one-time secret and otpauth URI.")
    @PostMapping("/me/totp/enroll")
    fun enroll(): ResponseEntity<TotpEnrollmentDto> {
        val enrollment = userService.beginTotpEnrollment(currentUserProvider.currentUser())
        return ResponseEntity.ok(
            TotpEnrollmentDto(secret = enrollment.base32Secret, otpauthUri = enrollment.otpauthUri)
        )
    }

    /**
     * Renders the acting admin's pending enrollment QR code as a PNG (the same `otpauth://` URI), so the
     * secret is never shipped to JavaScript purely to draw the code and the QR survives a page reload.
     */
    @Operation(summary = "The acting admin's pending enrollment QR code (PNG).")
    @GetMapping("/me/totp/qr.png", produces = [MediaType.IMAGE_PNG_VALUE])
    fun qrCode(): ResponseEntity<ByteArray> {
        val admin = currentUserProvider.currentUser()
        val secret =
            admin.totpSecret ?: throw ConflictException("Start two-factor enrollment before scanning the code.")
        val uri = totpService.otpauthUri(secret, admin.loginName)
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(qrCodeService.pngQrCode(uri, QR_SIZE_PX))
    }

    /**
     * Activates the acting admin's pending enrollment after verifying a current code, then upgrades their
     * session cookie from enrollment-only to a full admin session in the same response, so they do not have
     * to sign in again.
     *
     * @param dto the current 6-digit authenticator code confirming the enrollment
     */
    @Operation(summary = "Activate two-factor with a current code; upgrades the session to full admin.")
    @PostMapping("/me/totp/activate")
    fun activate(
        @RequestBody
        @Valid dto: TotpActivateRequestDto
    ): ResponseEntity<Void> {
        val enabled: User = userService.enableTotp(requireNotNull(dto.code), currentUserProvider.currentUser())
        val token = adminSessionFactory.mintToken(enabled.loginName, listOf("ADMIN"))
        return ResponseEntity
            .noContent()
            .header(HttpHeaders.SET_COOKIE, adminSessionFactory.sessionCookie(token).toString())
            .build()
    }

    /** Clears the acting admin's own second factor (they must re-enroll on their next login). */
    @Operation(summary = "Disable the acting admin's own second factor.")
    @DeleteMapping("/me/totp")
    fun disable(): ResponseEntity<Void> {
        userService.disableTotp(currentUserProvider.currentUser())
        return ResponseEntity.noContent().build()
    }

    /**
     * Clears another admin's second factor as a recovery reset (for a colleague who lost their authenticator);
     * they must re-enroll on their next login. Full-admin only.
     *
     * @param id the id of the user whose second factor to clear
     */
    @Operation(summary = "Reset another admin's second factor (recovery).")
    @DeleteMapping("/{id}/totp")
    fun reset(
        @PathVariable id: UUID
    ): ResponseEntity<Void> {
        userService.resetTotpFor(id, currentUserProvider.currentUser())
        return ResponseEntity.noContent().build()
    }

    private companion object {
        private const val QR_SIZE_PX = 512
    }
}
