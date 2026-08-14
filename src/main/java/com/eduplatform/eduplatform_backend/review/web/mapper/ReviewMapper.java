package com.eduplatform.eduplatform_backend.review.web.mapper;

import com.eduplatform.eduplatform_backend.review.domain.CourseReview;
import com.eduplatform.eduplatform_backend.review.web.dto.CourseReviewDto;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface ReviewMapper {

    @Mapping(target = "courseId", source = "course.id")
    @Mapping(target = "userId",   source = "user.id")
    @Mapping(target = "authorName",
             expression = "java(review.getUser() == null ? null : review.getUser().getFirstName() + \" \" + review.getUser().getLastName())")
    CourseReviewDto toDto(CourseReview review);
}
