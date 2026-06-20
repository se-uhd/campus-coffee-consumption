package de.seuhd.campuscoffee

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

/**
 * Main class to start the Spring Boot application.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
class Application

/**
 * Starts the Spring Boot application.
 *
 * @param args the command line arguments passed to the Spring application
 */
fun main(args: Array<String>) {
    runApplication<Application>(*args)
}
