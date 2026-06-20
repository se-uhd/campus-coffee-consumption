package de.seuhd.campuscoffee.data.mapper

import de.seuhd.campuscoffee.data.persistence.entities.UserEntity
import de.seuhd.campuscoffee.domain.model.objects.User
import org.mapstruct.Mapper
import org.mapstruct.Mapping
import org.mapstruct.MappingTarget
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean

/**
 * MapStruct mapper between [User] domain objects and [UserEntity] persistence entities. The role set and
 * the stored [User.passwordHash] map by name in both directions; the raw [User.password] has no entity
 * counterpart (it is hashed in the domain before persistence) and is ignored on the way in and left null
 * on the way out.
 */
@Mapper(componentModel = "spring")
@ConditionalOnMissingBean // prevent IntelliJ warning about duplicate beans
interface UserEntityMapper : EntityMapper<User, UserEntity> {
    @Mapping(target = "password", ignore = true)
    override fun fromEntity(source: UserEntity): User

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    override fun updateEntity(
        source: User,
        @MappingTarget target: UserEntity
    )
}
