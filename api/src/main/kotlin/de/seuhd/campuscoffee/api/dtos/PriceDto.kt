package de.seuhd.campuscoffee.api.dtos

/**
 * Response DTO for the global coffee price: the current amount per cup in euro cents. Plain (no [Dto] id
 * base) because the price is a singleton with no client-facing id.
 */
data class PriceDto(
    val amountCents: Int
)
