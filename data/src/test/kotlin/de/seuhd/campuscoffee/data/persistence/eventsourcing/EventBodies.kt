package de.seuhd.campuscoffee.data.persistence.eventsourcing

import java.util.UUID

/**
 * Builds event body maps (matching the `jsonb` shapes the serializers produce) for the projector tests:
 * a full-state User body, a flattened CoffeeConsumption body (the user as an id), and a DELETE body.
 */
object EventBodies {
    private const val TIMESTAMP = "2025-01-01T00:00:00"

    fun user(
        id: UUID,
        loginName: String = "member",
        emailAddress: String = "member@se.de",
        capabilityToken: String = "token"
    ): Map<String, Any?> =
        mapOf(
            "id" to id.toString(),
            "createdAt" to TIMESTAMP,
            "updatedAt" to TIMESTAMP,
            "loginName" to loginName,
            "emailAddress" to emailAddress,
            "firstName" to "First",
            "lastName" to "Last",
            "role" to "USER",
            "active" to true,
            "capabilityToken" to capabilityToken,
            "passwordHash" to "{noop}hash"
        )

    fun consumption(
        id: UUID,
        userId: UUID,
        count: Int = 0
    ): Map<String, Any?> =
        mapOf(
            "id" to id.toString(),
            "createdAt" to TIMESTAMP,
            "updatedAt" to TIMESTAMP,
            "userId" to userId.toString(),
            "count" to count
        )

    fun delete(id: UUID): Map<String, Any?> = mapOf("id" to id.toString())
}
