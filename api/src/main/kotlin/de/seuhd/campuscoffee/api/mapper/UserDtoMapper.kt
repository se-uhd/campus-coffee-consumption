package de.seuhd.campuscoffee.api.mapper

import de.seuhd.campuscoffee.api.dtos.UserDto
import de.seuhd.campuscoffee.domain.model.User
import org.mapstruct.Mapper
import org.mapstruct.Mapping
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean

/**
 * MapStruct mapper between [User] domain objects and [UserDto]s.
 *
 * The raw [UserDto.password] is mapped into the domain only on the way in; the stored `passwordHash` has
 * no DTO counterpart, so it is never serialized. The single [User.role] and [User.active] flag map by
 * name. Two fields are deliberately not auto-mapped: the secret `capabilityToken` is server-assigned and
 * never accepted from a client (the controller assembles the read-only [UserDto.capabilityUrl] from it
 * instead), and the role/active policy is enforced in `UserServiceImpl`, not here.
 */
@Mapper(componentModel = "spring")
@ConditionalOnMissingBean // prevent IntelliJ warning about duplicate beans
interface UserDtoMapper : DtoMapper<User, UserDto> {
    @Mapping(target = "capabilityUrl", ignore = true) // assembled by the controller from the token, not a User field
    override fun fromDomain(source: User): UserDto

    @Mapping(target = "passwordHash", ignore = true)
    @Mapping(target = "capabilityToken", ignore = true) // server-assigned; never taken from the request body
    override fun toDomain(source: UserDto): User
}
