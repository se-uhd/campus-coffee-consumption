package de.seuhd.campuscoffee.configuration

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Bootstrap-admin configuration, bound from `campus-coffee.bootstrap-admin.*`. On first startup, when no
 * admin exists and [loginName]/[password] are set, the application creates one admin so a fresh prod
 * deployment is reachable without fixtures (which are off in prod). When the credentials are absent (e.g.
 * in dev, where the fixtures already seed an admin) the bootstrap does nothing.
 *
 * @property loginName    the admin's login name (the bootstrap is a no-op when blank).
 * @property password     the admin's initial raw password (the bootstrap is a no-op when blank).
 * @property emailAddress the admin's email address.
 * @property firstName    the admin's first name.
 * @property lastName     the admin's last name.
 */
@ConfigurationProperties("campus-coffee.bootstrap-admin")
data class BootstrapAdminProperties(
    val loginName: String? = null,
    val password: String? = null,
    val emailAddress: String = "admin@se.uni-heidelberg.de",
    val firstName: String = "Bootstrap",
    val lastName: String = "Admin"
)
