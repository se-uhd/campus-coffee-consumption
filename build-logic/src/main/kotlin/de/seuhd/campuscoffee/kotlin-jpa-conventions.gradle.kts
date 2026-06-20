package de.seuhd.campuscoffee

// The Kotlin JPA plugin: a no-arg constructor for, and opening of, the @Entity/@Embeddable/
// @MappedSuperclass classes, as Hibernate requires. Applied only to the data module.
plugins {
    kotlin("plugin.jpa")
}
