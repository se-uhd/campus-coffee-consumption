package de.seuhd.campuscoffee.data

import de.seuhd.campuscoffee.domain.model.ChangeNoteContext
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.context.annotation.Bean
import java.time.Clock

/**
 * Boot configuration used only by the data module's integration tests. It component-scans the data
 * layer so the real repositories, mappers, constraint mappings, id generator, and data services are
 * wired exactly as in production, without pulling in the api or application layers. The
 * domain-owned [ChangeNoteContext] the event store depends on is supplied as a bean here, since the
 * domain package is outside the data component scan, as is the [Clock] the TOTP adapter reads (both are
 * provided by the application module in production).
 */
@SpringBootApplication
@ConfigurationPropertiesScan
class DataTestApplication {
    /** The note context the event store reads; a no-op holder for the data tests (no admin note is set). */
    @Bean
    fun changeNoteContext(): ChangeNoteContext = ChangeNoteContext()

    /** The clock the TOTP adapter reads for the current time step; the real UTC clock for the data tests. */
    @Bean
    fun clock(): Clock = Clock.systemUTC()
}
