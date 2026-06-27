package de.seuhd.campuscoffee.data.persistence

import com.fasterxml.jackson.annotation.JsonIgnore
import de.seuhd.campuscoffee.domain.model.CoffeeConsumption
import de.seuhd.campuscoffee.domain.model.Expense
import de.seuhd.campuscoffee.domain.model.Payment
import de.seuhd.campuscoffee.domain.model.User
import tools.jackson.core.JsonGenerator
import tools.jackson.databind.SerializationContext
import tools.jackson.databind.ValueSerializer
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.module.SimpleModule
import tools.jackson.module.kotlin.KotlinModule

/**
 * The Jackson mapper for the event log. The same mapper builds the event [body] map and, via Hibernate's
 * `hibernate.type.json_format_mapper`, serializes that map into the `jsonb` column and reads it back, so a
 * value stored in a body reads back as the same value. It uses Jackson version 3 (the major Spring
 * Framework 7 ships), which renders `java.time` as ISO-8601 strings by default, so the timestamps survive
 * unchanged without extra configuration.
 *
 * The Kotlin module is registered because the domain models are immutable `data class`es: on a rebuild the
 * projector reconstructs them from a body, which needs Kotlin's primary-constructor binding.
 *
 * Several payload rules are enforced here:
 * - the raw [User.password] is always dropped. It is only an input (the client sends it, the domain hashes
 *   it into [User.passwordHash] and discards it), so it is never stored and must never reach an event.
 * - a [CoffeeConsumption] is flattened to its user id, so a consumption event records a reference rather
 *   than a copy of the user (a copy would leak the user's `passwordHash`). A `User` event does keep
 *   `passwordHash`, so a login still works after a rebuild from the log.
 * - an [Expense] is flattened to its buyer's id (`buyerUserId`) and a [Payment] to its user's id
 *   (`userId`, written as JSON null for a pure kitty adjustment), for the same reason. A [CoffeePrice] has
 *   no references, so it needs no custom serializer and is written field for field.
 *
 * It is a singleton rather than a Spring `ObjectMapper` bean, with its event-specific serializers and
 * mixin kept off the application's general-purpose mapper.
 */
object EventJsonMapper {
    val instance: JsonMapper = build()

    /**
     * Builds the event mapper: the Kotlin module, the serializer that flattens a consumption to its user
     * id, and the mixin that drops the raw password.
     */
    private fun build(): JsonMapper =
        JsonMapper
            .builder()
            .addModule(KotlinModule.Builder().build())
            .addModule(
                SimpleModule()
                    .addSerializer(CoffeeConsumption::class.java, CoffeeConsumptionEventSerializer())
                    .addSerializer(Expense::class.java, ExpenseEventSerializer())
                    .addSerializer(Payment::class.java, PaymentEventSerializer())
            ).addMixIn(User::class.java, UserSecretsMixin::class.java)
            .build()

    /** Drops the raw [User.password] from a serialized user, so it can never reach an event (see the class doc). */
    @Suppress("unused")
    private abstract class UserSecretsMixin {
        @get:JsonIgnore
        abstract val password: String?
    }

    /**
     * Serializes a [CoffeeConsumption] with its user flattened to an id (`userId`). The projector resolves
     * that id back to the user read model row when it applies the event.
     */
    private class CoffeeConsumptionEventSerializer : ValueSerializer<CoffeeConsumption>() {
        override fun serialize(
            value: CoffeeConsumption,
            gen: JsonGenerator,
            ctxt: SerializationContext
        ) {
            gen.writeEntityHeader(value.id, value.createdAt, value.updatedAt)
            gen.writeName("userId")
            gen.writePOJO(value.user.id)
            gen.writeName("count")
            gen.writeNumber(value.count)
            gen.writeEndObject()
        }
    }

    /**
     * Serializes an [Expense] with its buyer flattened to an id (`buyerUserId`). The projector resolves that
     * id back to the user read model row when it applies the event.
     */
    private class ExpenseEventSerializer : ValueSerializer<Expense>() {
        override fun serialize(
            value: Expense,
            gen: JsonGenerator,
            ctxt: SerializationContext
        ) {
            gen.writeEntityHeader(value.id, value.createdAt, value.updatedAt)
            gen.writeName("buyerUserId")
            gen.writePOJO(value.buyer.id)
            gen.writeName("weightGrams")
            gen.writeNumber(value.weightGrams)
            gen.writeName("amountCents")
            gen.writeNumber(value.amountCents)
            gen.writeName("privateAmountCents")
            gen.writeNumber(value.privateAmountCents)
            gen.writeName("kittyAmountCents")
            gen.writeNumber(value.kittyAmountCents)
            gen.writeName("note")
            gen.writePOJO(value.note)
            gen.writeEndObject()
        }
    }

    /**
     * Serializes a [Payment] with its user flattened to an id (`userId`), written as JSON null for a pure
     * kitty adjustment (no user). The projector resolves a non-null id back to the user read model row.
     */
    private class PaymentEventSerializer : ValueSerializer<Payment>() {
        override fun serialize(
            value: Payment,
            gen: JsonGenerator,
            ctxt: SerializationContext
        ) {
            gen.writeEntityHeader(value.id, value.createdAt, value.updatedAt)
            gen.writeName("userId")
            gen.writePOJO(value.user?.id)
            gen.writeName("amountCents")
            gen.writeNumber(value.amountCents)
            gen.writeName("note")
            gen.writePOJO(value.note)
            gen.writeEndObject()
        }
    }
}

/**
 * Writes the object start and the `id`/`createdAt`/`updatedAt` header that every flattened entity event body
 * shares, before its per-type fields. Centralizes the prefix the custom serializers would otherwise repeat;
 * the values go through `writePOJO`, so the timestamps serialize via the same `java.time` path as before.
 *
 * @param id the entity id
 * @param createdAt the entity's creation timestamp
 * @param updatedAt the entity's last-update timestamp
 */
private fun JsonGenerator.writeEntityHeader(
    id: Any?,
    createdAt: Any?,
    updatedAt: Any?
) {
    writeStartObject()
    writeName("id")
    writePOJO(id)
    writeName("createdAt")
    writePOJO(createdAt)
    writeName("updatedAt")
    writePOJO(updatedAt)
}
