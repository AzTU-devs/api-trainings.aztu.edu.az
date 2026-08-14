package com.eduplatform.eduplatform_backend.identity.web.mapper;

import com.eduplatform.eduplatform_backend.identity.domain.User;
import com.eduplatform.eduplatform_backend.identity.domain.UserIdentity;
import com.eduplatform.eduplatform_backend.identity.web.dto.UserDto;
import com.eduplatform.eduplatform_backend.identity.web.dto.UserIdentityDto;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.Set;

@Mapper(componentModel = "spring")
public interface UserMapper {

    @Mapping(target = "emailVerified", expression = "java(user.getEmailVerifiedAt() != null)")
    @Mapping(target = "roles",       source = "roles")
    @Mapping(target = "permissions", source = "permissions")
    UserDto toDto(User user, Set<String> roles, Set<String> permissions);

    UserIdentityDto toIdentityDto(UserIdentity identity);
}
