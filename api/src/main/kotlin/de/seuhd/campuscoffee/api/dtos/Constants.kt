package de.seuhd.campuscoffee.api.dtos

/**
 * The upper bound, in euro cents, on any single money amount the API accepts: 1,000 EUR. A fat-finger
 * guardrail (an absurd amount is rejected with 400), well under `Int.MAX_VALUE`, shared by the money request
 * DTOs as their `@Max`. Not a business rule, just a sanity cap.
 */
const val MAX_MONEY_CENTS: Long = 1_000_00

/**
 * The lower bound, in grams, on the weight of a single bean purchase: 100 g. A purchase below this is almost
 * certainly a fat-finger (a real bag of beans is at least this heavy), so it is rejected with 400. Shared by
 * the expense request DTOs as their `@Min`.
 */
const val MIN_WEIGHT_GRAMS: Long = 100

/**
 * The upper bound, in grams, on the weight of a single bean purchase: 1,000 g (one kilogram). A fat-finger
 * guardrail (an implausibly large weight is rejected with 400), shared by the expense request DTOs as their
 * `@Max`. Not a business rule, just a sanity cap.
 */
const val MAX_WEIGHT_GRAMS: Long = 1_000

/**
 * The minimum length of an admin password (only an admin has one; a user authenticates with their
 * capability link). Length is favored over forced composition, so the floor is generous. Shared by the
 * [UserDto] `@Size` and the bootstrap-admin check so the policy lives in one place.
 */
const val MIN_PASSWORD_LENGTH: Int = 24

/** The maximum length of an admin password. */
const val MAX_PASSWORD_LENGTH: Int = 255

/**
 * The complexity rule for an admin password: at least one lowercase letter, one uppercase letter, and one
 * digit (length is enforced separately by [MIN_PASSWORD_LENGTH]). Shared by the [UserDto] `@Pattern` and the
 * bootstrap-admin check. A `const` so it can be used as the `@Pattern` regexp.
 */
const val PASSWORD_COMPLEXITY_PATTERN: String = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).*$"
