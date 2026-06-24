package de.seuhd.campuscoffee.api.dtos

/**
 * The upper bound, in euro cents, on any single money amount the API accepts: 1,000 EUR. A fat-finger
 * guardrail (an absurd amount is rejected with 400), well under `Int.MAX_VALUE`, shared by the money request
 * DTOs as their `@Max`. Not a business rule, just a sanity cap.
 */
const val MAX_MONEY_CENTS: Long = 1_000_00

/**
 * The upper bound, in grams, on the weight of a single bean purchase: 1,000 g (one kilogram). A fat-finger
 * guardrail (an implausibly large weight is rejected with 400), shared by the expense request DTOs as their
 * `@Max`. Not a business rule, just a sanity cap.
 */
const val MAX_WEIGHT_GRAMS: Long = 1_000
