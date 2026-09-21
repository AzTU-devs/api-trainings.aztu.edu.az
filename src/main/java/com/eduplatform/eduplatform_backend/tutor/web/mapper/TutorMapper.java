package com.eduplatform.eduplatform_backend.tutor.web.mapper;

import com.eduplatform.eduplatform_backend.catalog.domain.Category;
import com.eduplatform.eduplatform_backend.tutor.domain.TutorProfile;
import com.eduplatform.eduplatform_backend.tutor.web.dto.TutorProfileDto;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Mapper(componentModel = "spring")
public interface TutorMapper {

    @Mapping(target = "userId",    source = "user.id")
    @Mapping(target = "firstName", source = "user.firstName")
    @Mapping(target = "lastName",  source = "user.lastName")
    @Mapping(target = "avatarMediaId", source = "avatar.id")
    @Mapping(target = "avatarUrl", expression = "java(avatarUrl(tutor))")
    @Mapping(target = "expertiseCategoryIds", source = "expertises", qualifiedByName = "categoryIds")
    TutorProfileDto toDto(TutorProfile tutor);

    @Named("categoryIds")
    default Set<UUID> categoryIds(Set<Category> categories) {
        return categories == null ? Set.of() : categories.stream().map(Category::getId).collect(Collectors.toSet());
    }

    /**
     * Where a browser fetches the portrait, mirroring a course's thumbnailUrl: relative, because
     * the API is reached under more than one host, and pointed at the anonymous media endpoint,
     * which serves it once the profile is approved. Reading the id off the lazy proxy costs no
     * extra select.
     */
    default String avatarUrl(TutorProfile tutor) {
        if (tutor == null || tutor.getAvatar() == null) return null;
        return "/api/public/media/" + tutor.getAvatar().getId() + "/content";
    }
}
