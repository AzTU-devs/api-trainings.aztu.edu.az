package com.eduplatform.eduplatform_backend.review.web.mapper;

import com.eduplatform.eduplatform_backend.identity.domain.User;
import com.eduplatform.eduplatform_backend.review.domain.CourseReview;
import com.eduplatform.eduplatform_backend.review.web.dto.CourseReviewDto;
import org.hibernate.Hibernate;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface ReviewMapper {

    /** For the author and for moderators: the review with the author's account id. */
    @Mapping(target = "courseId", source = "course.id")
    @Mapping(target = "userId",   source = "user.id")
    @Mapping(target = "authorName", expression = "java(authorName(review))")
    CourseReviewDto toDto(CourseReview review);

    /**
     * For the public course page, which anyone can read: no {@code userId}. The internal id of
     * every reviewer's account had no use there (the site shows the name) and let anyone tie a
     * person's reviews together across courses. The field stays in the response, as null, so the
     * shape the site reads is unchanged; it never read the id.
     */
    @Mapping(target = "courseId", source = "course.id")
    @Mapping(target = "userId",   ignore = true)
    @Mapping(target = "authorName", expression = "java(authorName(review))")
    CourseReviewDto toPublicDto(CourseReview review);

    /**
     * CourseReview.authorName, which a review read from the database carries. A review written in
     * this request was saved, not read, so it has none yet; its author is then the account the
     * service has just loaded. A lazy proxy is never initialised here: the session is closed by
     * the time the mapper runs, and the account may be a deleted one.
     */
    default String authorName(CourseReview review) {
        if (review.getAuthorName() != null) return review.getAuthorName();
        User author = review.getUser();
        return author != null && Hibernate.isInitialized(author)
                ? author.getFirstName() + " " + author.getLastName()
                : null;
    }
}
