package de.seuhd.campuscoffee.data.adapters

import de.seuhd.campuscoffee.domain.ports.data.PasswordHasherService
import org.springframework.security.crypto.factory.PasswordEncoderFactories
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Component

/**
 * Data-layer adapter for the [PasswordHasherService] port, backed by Spring Security's delegating password
 * encoder (BCrypt by default; the `{id}` prefix records which algorithm produced a hash). It creates its
 * own encoder rather than depending on a bean wired elsewhere, so nothing outside the data layer needs
 * Spring Security's crypto types.
 */
@Component
class PasswordHasherServiceImpl : PasswordHasherService {
    private val encoder: PasswordEncoder = PasswordEncoderFactories.createDelegatingPasswordEncoder()

    override fun hash(rawPassword: String): String = encoder.encode(rawPassword)!!

    override fun matches(
        rawPassword: String,
        storedHash: String
    ): Boolean = encoder.matches(rawPassword, storedHash)
}
