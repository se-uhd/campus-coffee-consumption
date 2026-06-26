package de.seuhd.campuscoffee.data.configuration

import de.seuhd.campuscoffee.domain.ports.ActorProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Provides a fallback [ActorProvider] for the event store when no security-aware adapter is present (e.g.
 * the data-layer integration tests, which run without the application's Spring Security wiring). It always
 * reports `"SYSTEM"`. The application layer contributes the real, `SecurityContext`-backed adapter, which
 * takes precedence via [ConditionalOnMissingBean].
 */
@Configuration
class EventContextConfiguration {
    /**
     * The fallback actor provider, reporting `"SYSTEM"`, used unless the application supplies a real one.
     *
     * @return an actor provider that always returns `"SYSTEM"`
     */
    @Bean
    @ConditionalOnMissingBean(ActorProvider::class)
    fun systemActorProvider(): ActorProvider = ActorProvider { SYSTEM_ACTOR }

    private companion object {
        private const val SYSTEM_ACTOR = "SYSTEM"
    }
}
