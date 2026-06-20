package de.seuhd.campuscoffee.data.configuration

import org.springframework.context.annotation.Configuration
import org.springframework.data.jpa.repository.config.EnableJpaRepositories

/**
 * Enables the Spring Data JPA repositories in the data layer (the read model repositories under
 * `persistence.repositories` and the event log repository under `persistence.eventsourcing`).
 */
@Configuration
@EnableJpaRepositories(basePackages = ["de.seuhd.campuscoffee.data.persistence"])
class RepositoryConfiguration
