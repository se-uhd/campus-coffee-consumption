package de.seuhd.campuscoffee.configuration

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Bootstrap-admin configuration, bound from `campus-coffee.bootstrap-admin.*`. On first startup, when no
 * admin exists and the credentials are configured, the application creates one admin so a fresh prod
 * deployment is reachable without fixtures (which are off in prod).
 *
 * The fields are nullable and the class carries no defaults: a field is null when its key is absent (so in
 * dev, where the block is not set, the bootstrap is off). The values live in `application.yaml`
 * (the prod block). [de.seuhd.campuscoffee.BootstrapAdminLoader] rejects a blank or too-short value, so a
 * partial or blank configuration fails fast rather than creating an admin with empty credentials.
 *
 * @property loginName    the admin's login name (null = the bootstrap is off).
 * @property password     the admin's initial raw password (null = the bootstrap is off).
 * @property emailAddress the admin's email address.
 * @property firstName    the admin's first name.
 * @property lastName     the admin's last name.
 */
@ConfigurationProperties("campus-coffee.bootstrap-admin")
data class BootstrapAdminProperties(
    val loginName: String?,
    val password: String?,
    val emailAddress: String?,
    val firstName: String?,
    val lastName: String?
)
