package com.eduplatform.eduplatform_backend.course.web.mapper;

import com.eduplatform.eduplatform_backend.catalog.domain.Category;
import com.eduplatform.eduplatform_backend.catalog.domain.Tag;
import com.eduplatform.eduplatform_backend.common.enums.CourseType;
import com.eduplatform.eduplatform_backend.course.domain.Course;
import com.eduplatform.eduplatform_backend.course.domain.CourseModule;
import com.eduplatform.eduplatform_backend.course.domain.Lesson;
import com.eduplatform.eduplatform_backend.course.domain.OfflineCourseDetails;
import com.eduplatform.eduplatform_backend.course.domain.OnlineCourseDetails;
import com.eduplatform.eduplatform_backend.tutor.domain.TutorProfile;
import com.eduplatform.eduplatform_backend.course.web.dto.*;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Mapper(componentModel = "spring")
public interface CourseMapper {

    @Mapping(target = "tutorId", source = "tutor.id")
    @Mapping(target = "tutorDisplayName", expression = "java(course.getTutor() == null ? null : course.getTutor().getUser().getFirstName() + \" \" + course.getTutor().getUser().getLastName())")
    @Mapping(target = "tutors", expression = "java(toTutorDtos(course))")
    @Mapping(target = "totalDurationSec", expression = "java(totalDurationSec(course))")
    @Mapping(target = "thumbnailUrl", expression = "java(thumbnailUrl(course))")
    CourseSummaryDto toSummaryDto(Course course);

    @Mapping(target = "tutorId", source = "tutor.id")
    @Mapping(target = "tutorDisplayName", expression = "java(course.getTutor() == null ? null : course.getTutor().getUser().getFirstName() + \" \" + course.getTutor().getUser().getLastName())")
    @Mapping(target = "thumbnailMediaId", source = "thumbnail.id")
    @Mapping(target = "trailerMediaId",   source = "trailer.id")
    @Mapping(target = "categoryIds", source = "categories", qualifiedByName = "categoryIds")
    @Mapping(target = "tagIds",      source = "tags",       qualifiedByName = "tagIds")
    @Mapping(target = "onlineDetails",  source = "onlineDetails")
    @Mapping(target = "offlineDetails", source = "offlineDetails")
    @Mapping(target = "modules", expression = "java(toModuleDtos(course.getModules()))")
    @Mapping(target = "tutors", expression = "java(toTutorDtos(course))")
    CourseDto toDto(Course course);

    OnlineDetailsDto toOnline(OnlineCourseDetails d);
    OfflineDetailsDto toOffline(OfflineCourseDetails d);

    @Mapping(target = "videoMediaId", source = "videoMedia.id")
    LessonDto toLessonDto(Lesson lesson);

    @Mapping(target = "lessons", expression = "java(toLessonDtos(module.getLessons()))")
    ModuleDto toModuleDto(CourseModule module);

    default List<ModuleDto> toModuleDtos(Set<CourseModule> modules) {
        if (modules == null) return List.of();
        return modules.stream()
                .sorted(Comparator.comparingInt(CourseModule::getOrderIndex))
                .map(this::toModuleDto)
                .toList();
    }

    default List<LessonDto> toLessonDtos(Set<Lesson> lessons) {
        if (lessons == null) return List.of();
        return lessons.stream()
                .sorted(Comparator.comparingInt(Lesson::getOrderIndex))
                .map(this::toLessonDto)
                .toList();
    }

    @Named("categoryIds")
    default Set<UUID> categoryIds(Set<Category> categories) {
        return categories == null ? Set.of() : categories.stream().map(Category::getId).collect(Collectors.toSet());
    }

    @Named("tagIds")
    default Set<UUID> tagIds(Set<Tag> tags) {
        return tags == null ? Set.of() : tags.stream().map(Tag::getId).collect(Collectors.toSet());
    }

    /**
     * Builds the teaching roster. The {@code authorized} flag is derived from
     * {@code course.getTutor()} rather than stored per row, so it cannot drift out
     * of sync with the column that actually governs edit permission.
     *
     * <p>Sorted by display name for stable output; a Set has no meaningful order and
     * an unstable roster makes responses noisy to diff and to cache.
     */
    default List<CourseTutorDto> toTutorDtos(Course course) {
        if (course == null || course.getTutors() == null) return List.of();
        UUID authorizedId = course.getTutor() == null ? null : course.getTutor().getId();
        return course.getTutors().stream()
                .map(t -> new CourseTutorDto(
                        t.getId(),
                        displayNameOf(t),
                        t.getId().equals(authorizedId)))
                .sorted(Comparator.comparing(CourseTutorDto::displayName,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    private static String displayNameOf(TutorProfile t) {
        if (t == null || t.getUser() == null) return null;
        return t.getUser().getFirstName() + " " + t.getUser().getLastName();
    }

    /**
     * Course length in seconds for both course types, so one catalog card and one
     * duration filter can speak about either. The offline conversion rounds half up,
     * matching Postgres' {@code round()} in the duration-bucket predicate — otherwise a
     * card could show a length the bucket it was filtered into does not cover.
     */
    default Integer totalDurationSec(Course course) {
        if (course == null) return null;
        if (course.getCourseType() == CourseType.OFFLINE) {
            OfflineCourseDetails offline = course.getOfflineDetails();
            if (offline == null || offline.getTotalHours() == null) return null;
            return offline.getTotalHours()
                    .multiply(BigDecimal.valueOf(3600))
                    .setScale(0, RoundingMode.HALF_UP)
                    .intValue();
        }
        OnlineCourseDetails online = course.getOnlineDetails();
        return online == null ? null : online.getTotalVideoSeconds();
    }

    /**
     * Where the browser can fetch the thumbnail. Relative, because the API is reached
     * under more than one host, and pointed at the anonymous media endpoint that serves
     * published-course marketing assets only. Reading the id off the lazy proxy costs
     * no extra select.
     */
    default String thumbnailUrl(Course course) {
        if (course == null || course.getThumbnail() == null) return null;
        return "/api/public/media/" + course.getThumbnail().getId() + "/content";
    }
}
