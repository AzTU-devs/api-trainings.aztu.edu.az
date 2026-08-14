package com.eduplatform.eduplatform_backend.review.service;

import com.eduplatform.eduplatform_backend.common.error.Errors;
import com.eduplatform.eduplatform_backend.course.domain.Course;
import com.eduplatform.eduplatform_backend.course.repo.CourseRepository;
import com.eduplatform.eduplatform_backend.enrollment.repo.EnrollmentRepository;
import com.eduplatform.eduplatform_backend.identity.domain.User;
import com.eduplatform.eduplatform_backend.identity.repo.UserRepository;
import com.eduplatform.eduplatform_backend.review.domain.CourseReview;
import com.eduplatform.eduplatform_backend.review.repo.CourseReviewRepository;
import com.eduplatform.eduplatform_backend.review.web.dto.ReviewCreateRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

@Service
public class ReviewService {

    private final CourseReviewRepository repo;
    private final CourseRepository courses;
    private final UserRepository users;
    private final EnrollmentRepository enrollments;

    public ReviewService(CourseReviewRepository repo, CourseRepository courses,
                         UserRepository users, EnrollmentRepository enrollments) {
        this.repo = repo;
        this.courses = courses;
        this.users = users;
        this.enrollments = enrollments;
    }

    @Transactional
    public CourseReview create(UUID userId, UUID courseId, ReviewCreateRequest req) {
        if (!enrollments.existsByUserIdAndCourseId(userId, courseId)) {
            throw Errors.forbidden("NOT_ENROLLED", "Only enrolled users can review this course");
        }
        if (repo.findByCourseIdAndUserId(courseId, userId).isPresent()) {
            throw Errors.conflict("ALREADY_REVIEWED", "You have already reviewed this course");
        }
        Course course = courses.findById(courseId)
                .orElseThrow(() -> Errors.notFound("COURSE_NOT_FOUND", "Course does not exist"));
        User user = users.findById(userId)
                .orElseThrow(() -> Errors.notFound("USER_NOT_FOUND", "User does not exist"));

        CourseReview review = CourseReview.builder()
                .course(course).user(user)
                .rating(req.rating())
                .title(req.title()).body(req.body())
                .visible(true)
                .build();
        review.setId(UUID.randomUUID());
        review = repo.save(review);

        refreshCourseRating(courseId, course);
        return review;
    }

    @Transactional(readOnly = true)
    public Page<CourseReview> forCourse(UUID courseId, Pageable pageable) {
        return repo.findAllByCourseIdAndVisibleTrueOrderByCreatedAtDesc(courseId, pageable);
    }

    private void refreshCourseRating(UUID courseId, Course course) {
        Object[] agg = repo.aggregateRating(courseId);
        Number avg = (Number) agg[0];
        Number cnt = (Number) agg[1];
        course.setRatingAvg(BigDecimal.valueOf(avg == null ? 0 : avg.doubleValue()).setScale(2, java.math.RoundingMode.HALF_UP));
        course.setRatingCount(cnt == null ? 0 : cnt.intValue());
        courses.save(course);
    }
}
