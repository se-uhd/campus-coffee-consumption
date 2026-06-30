package de.seuhd.campuscoffee.configuration

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/**
 * Provides the [Clock] the read side derives "now" from when it computes the landing's cup-stat windows
 * ("cups today" / "this week"). Injecting a single clock keeps "now" deterministic under test (a fixed clock
 * is substituted) while production reads the real system clock.
 */
@Configuration
class ClockConfiguration {
    /** The production clock: the real system clock in UTC (the event timestamps the windows compare are UTC). */
    @Bean
    fun clock(): Clock = Clock.systemUTC()
}
