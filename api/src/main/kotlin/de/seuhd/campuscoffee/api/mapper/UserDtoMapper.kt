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
 * name. Server-owned fields are deliberately not auto-mapped from a request: the secret `capabilityToken`
 * (the controller assembles the read-only [UserDto.capabilityUrl] from it instead), the encrypted
 * `totpSecret` (which has no DTO counterpart and is never exposed), and `totpEnabled` (read-only in the DTO;
 * only the two-factor endpoints change it). The role/active and second-factor policies are enforced in
 * `UserServiceImpl`, not here.
 */
@Mapper(componentModel = "spring")
@ConditionalOnMissingBean // prevent IntelliJ warning about duplicate beans
interface UserDtoMapper : DtoMapper<User, UserDto> {
    @Mapping(target = "capabilityUrl", ignore = true) // assembled by the controller from the token, not a User field
    override fun fromDomain(source: User): UserDto

    @Mapping(target = "passwordHash", ignore = true)
    @Mapping(target = "capabilityToken", ignore = true) // server-assigned; never taken from the request body
    @Mapping(target = "totpSecret", ignore = true) // server-owned second-factor secret; never from a request
    @Mapping(target = "totpEnabled", ignore = true) // changed only by the two-factor endpoints, never a generic edit
    override fun toDomain(source: UserDto): User
}
