package de.seuhd.campuscoffee.api.mapper

import de.seuhd.campuscoffee.api.dtos.ActivityEntryDto
import de.seuhd.campuscoffee.api.dtos.GlobalActivityEntryDto
import de.seuhd.campuscoffee.api.dtos.KittyDto
import de.seuhd.campuscoffee.api.dtos.PriceChangeDto
import de.seuhd.campuscoffee.api.dtos.UserBalanceDto
import de.seuhd.campuscoffee.api.dtos.UserSummaryDto
import de.seuhd.campuscoffee.domain.model.ActivityEntry
import de.seuhd.campuscoffee.domain.model.GlobalActivityEntry
import de.seuhd.campuscoffee.domain.model.PriceChange
import de.seuhd.campuscoffee.domain.model.UserBalance
import de.seuhd.campuscoffee.domain.model.UserSummary
import de.seuhd.campuscoffee.domain.model.persistedId
import org.mapstruct.Mapper
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean

/**
 * MapStruct mapper assembling the read-side money response DTOs from the domain (one-way, domain to DTO):
 * activity entries, a user's summary, the price history, the kitty, and the balance overview.
 */
@Mapper(componentModel = "spring")
@ConditionalOnMissingBean // prevent IntelliJ warning about duplicate beans
interface AccountingDtoMapper {
    /**
     * Maps a single activity entry to its response DTO.
     *
     * @param entry the activity entry to map
     */
    fun toEntryDto(entry: ActivityEntry): ActivityEntryDto

    /**
     * Maps a page of activity entries to their response DTOs.
     *
     * @param entries the activity entries to map
     */
    fun toEntryDtos(entries: List<ActivityEntry>): List<ActivityEntryDto>

    /**
     * Maps a single global activity entry (the admin all-users feed) to its response DTO.
     *
     * @param entry the global activity entry to map
     */
    fun toGlobalEntryDto(entry: GlobalActivityEntry): GlobalActivityEntryDto

    /**
     * Maps a page of global activity entries to their response DTOs.
     *
     * @param entries the global activity entries to map
     */
    fun toGlobalEntryDtos(entries: List<GlobalActivityEntry>): List<GlobalActivityEntryDto>

    /**
     * Maps a user summary to its response DTO.
     *
     * @param summary the user summary to map
     */
    fun toSummaryDto(summary: UserSummary): UserSummaryDto

    /**
     * Maps one price-history entry to its response DTO.
     *
     * @param change the price change to map
     */
    fun toPriceChangeDto(change: PriceChange): PriceChangeDto

    /**
     * Maps the price history to its response DTOs.
     *
     * @param changes the price changes to map
     */
    fun toPriceChangeDtos(changes: List<PriceChange>): List<PriceChangeDto>

    /**
     * Assembles the kitty response from the balance and a page of its movements.
     *
     * @param balanceCents the current kitty balance in euro cents
     * @param entries      the kitty movements (newest first) to include
     */
    fun toKittyDto(
        balanceCents: Long,
        entries: List<ActivityEntry>
    ): KittyDto = KittyDto(balanceCents, toEntryDtos(entries))

    /**
     * Maps a balance-overview row to its response DTO, flattening the user.
     *
     * @param balance the user balance to map
     */
    fun toBalanceDto(balance: UserBalance): UserBalanceDto =
        UserBalanceDto(
            userId = balance.user.persistedId,
            loginName = balance.user.loginName,
            firstName = balance.user.firstName,
            lastName = balance.user.lastName,
            count = balance.count,
            balanceCents = balance.balanceCents
        )

    /**
     * Maps the balance overview to its response DTOs.
     *
     * @param balances the user balances to map
     */
    fun toBalanceDtos(balances: List<UserBalance>): List<UserBalanceDto> = balances.map { toBalanceDto(it) }
}
