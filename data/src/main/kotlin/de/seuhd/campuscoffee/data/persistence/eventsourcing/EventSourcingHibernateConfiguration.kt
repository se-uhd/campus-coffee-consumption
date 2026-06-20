package de.seuhd.campuscoffee.data.persistence.eventsourcing

import org.hibernate.cfg.MappingSettings
import org.hibernate.type.format.jackson.Jackson3JsonFormatMapper
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Makes Hibernate serialize the `events.body` `jsonb` column with the same Jackson mapper that builds the
 * event body ([EventJsonMapper]). Two Jackson majors are on the classpath during the Spring Framework 6-to-7
 * migration: Spring 7 ships the new Jackson 3 (`tools.jackson`), while Jackson 2 (`com.fasterxml.jackson`)
 * still arrives transitively (e.g. via Flyway). This will end once the ecosystem finishes moving to
 * Jackson 3. While both are present Hibernate would pick one itself, and a `Map` written through one mapper
 * could read back differently through the other, so we pin `hibernate.type.json_format_mapper` to our
 * mapper.
 *
 * It is the only `jsonb` column, so this affects nothing else.
 */
@Configuration
class EventSourcingHibernateConfiguration {
    /**
     * Pins Hibernate's `hibernate.type.json_format_mapper` to [EventJsonMapper], so the `events.body` `jsonb`
     * column is serialized and read back with the same mapper that builds the event body.
     *
     * @return the customizer that sets the JSON format mapper on the Hibernate properties
     */
    @Bean
    fun eventJsonFormatMapperCustomizer(): HibernatePropertiesCustomizer =
        HibernatePropertiesCustomizer { properties ->
            properties[MappingSettings.JSON_FORMAT_MAPPER] = Jackson3JsonFormatMapper(EventJsonMapper.instance)
        }
}
