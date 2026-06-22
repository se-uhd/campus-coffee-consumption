package de.seuhd.campuscoffee.api.dtos

/**
 * A sane upper bound, in euro cents, on any single money amount accepted by the API: 100,000 EUR. It is a
 * fat-finger guardrail (an absurd amount is rejected with a 400), well under `Int.MAX_VALUE`, shared by the
 * money request DTOs as their `@Max`. Not a business rule — just a sanity cap.
 */
const val MAX_MONEY_CENTS: Long = 100_000_00

/**
 * A sane upper bound, in grams, on the weight of a single bean purchase: 1,000,000 g (one metric tonne). A
 * fat-finger guardrail (an implausibly large weight is rejected with a 400), shared by the expense request
 * DTOs as their `@Max`. Not a business rule — just a sanity cap.
 */
const val MAX_WEIGHT_GRAMS: Long = 1_000_000
