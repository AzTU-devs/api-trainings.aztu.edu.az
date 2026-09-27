package com.eduplatform.eduplatform_backend.review.service;

import com.eduplatform.eduplatform_backend.audit.service.AuditService;
import com.eduplatform.eduplatform_backend.common.error.Errors;
import com.eduplatform.eduplatform_backend.course.domain.Course;
import com.eduplatform.eduplatform_backend.course.repo.CourseRepository;
import com.eduplatform.eduplatform_backend.course.service.CourseAccess;
import com.eduplatform.eduplatform_backend.enrollment.repo.EnrollmentRepository;
import com.eduplatform.eduplatform_backend.identity.domain.User;
import com.eduplatform.eduplatform_backend.identity.repo.UserRepository;
import com.eduplatform.eduplatform_backend.review.domain.CourseReview;
import com.eduplatform.eduplatform_backend.review.repo.CourseReviewRepository;
import com.eduplatform.eduplatform_backend.review.web.dto.ReviewCreateRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class ReviewService {

    /**
     * The whole of what an anonymous caller may sort a course's reviews by, keyed by the
     * property normalised as the catalogue normalises it (camelCase or snake_case, any letter
     * case) and mapped to the entity property JPA orders by. Both are fields every review in the
     * response shows. Nothing else is listed — above all no nested path such as
     * {@code user.email}: ordering public results by a field the response does not show turns
     * the ordering into an oracle for that field, here the reviewers' private email addresses.
     */
    private static final Map<String, String> SORTABLE_PROPERTIES = Map.of(
            "createdat", "createdAt",
            "rating", "rating");

    /** What the course page shows when it names no order, and the fallback under any other key. */
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("createdAt"));

    /**
     * Unique last key: reviews that tie on every other key would otherwise be ordered
     * arbitrarily per query, so one could repeat on page 2 and be missed on page 1.
     */
    private static final Sort TIEBREAKER = Sort.by(Sort.Order.desc("id"));

    private final CourseReviewRepository repo;
    private final CourseRepository courses;
    private final UserRepository users;
    private final EnrollmentRepository enrollments;
    private final AuditService audit;

    public ReviewService(CourseReviewRepository repo, CourseRepository courses,
                         UserRepository users, EnrollmentRepository enrollments, AuditService audit) {
        this.repo = repo;
        this.courses = courses;
        this.users = users;
        this.enrollments = enrollments;
        this.audit = audit;
    }

    /**
     * A review by a participant who holds a place on the course. A removed participant keeps a
     * CANCELLED enrolment, which used to be enough to post a review and move the course's rating.
     */
    @Transactional
    public CourseReview create(UUID userId, UUID courseId, ReviewCreateRequest req) {
        if (!enrollments.existsByUserIdAndCourseIdAndStatusIn(userId, courseId, CourseAccess.PLACE_HOLDING)) {
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

    /**
     * A page of a course's visible reviews, newest first unless the caller sorts by a
     * whitelisted property; any other {@code sort} is a 400 INVALID_SORT_PROPERTY, answered before
     * the query runs, the same code the catalogue uses.
     */
    @Transactional(readOnly = true)
    public Page<CourseReview> forCourse(UUID courseId, Pageable pageable) {
        Pageable ordered = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                publicOrder(pageable.getSort()));
        return repo.findVisibleByCourseId(courseId, ordered);
    }

    /**
     * The client's sort, each key checked against {@link #SORTABLE_PROPERTIES} and rebuilt from
     * the canonical property and the direction alone — the snake_case and mixed-case spellings
     * accepted here are not names JPA resolves — then newest-first and the id tiebreaker.
     */
    private static Sort publicOrder(Sort requested) {
        Sort order = Sort.unsorted();
        boolean byCreatedAt = false;
        for (Sort.Order requestedOrder : requested) {
            String property = SORTABLE_PROPERTIES.get(
                    requestedOrder.getProperty().replace("_", "").toLowerCase(Locale.ROOT));
            if (property == null) {
                throw Errors.badRequest("INVALID_SORT_PROPERTY", "Reviews cannot be sorted by '"
                        + requestedOrder.getProperty() + "'; use one of createdAt, rating");
            }
            order = order.and(Sort.by(requestedOrder.getDirection(), property));
            byCreatedAt |= property.equals("createdAt");
        }
        // Skipped when the client already ordered by createdAt, so its chosen direction is not
        // followed by a contradicting, redundant key.
        if (!byCreatedAt) {
            order = order.and(NEWEST_FIRST);
        }
        return order.and(TIEBREAKER);
    }

    /**
     * Reviews for moderation, hidden ones included, newest first. Both filters are optional.
     * The order is fixed: this is an admin list, not a search, and a free-form sort would only
     * reach the query as a column name.
     */
    @Transactional(readOnly = true)
    public Page<CourseReview> forModeration(UUID courseId, Boolean visible, Pageable pageable) {
        return repo.searchForModeration(courseId, visible,
                PageRequest.of(pageable.getPageNumber(), pageable.getPageSize()));
    }

    /**
     * Hides a review from the course page, or shows it again. Hidden rather than deleted, so the
     * decision can be undone and the audit trail keeps what was said. The course's rating is
     * recomputed either way: it counts visible reviews only, and it used to be recomputed on
     * create alone, so review:moderate had nothing it could act on.
     */
    @Transactional
    public CourseReview setVisible(UUID reviewId, boolean visible) {
        CourseReview review = repo.findById(reviewId)
                .orElseThrow(() -> Errors.notFound("REVIEW_NOT_FOUND", "Review does not exist"));
        boolean before = review.isVisible();
        review.setVisible(visible);
        repo.saveAndFlush(review);
        Course course = review.getCourse();
        refreshCourseRating(course.getId(), course);
        audit.record(AuditService.Actions.UPDATE, "COURSE_REVIEW", review.getId(),
                AuditService.snapshot("visible", before),
                AuditService.snapshot("visible", visible, "courseId", course.getId().toString()));
        // The author is not loaded for the response: the mapper reads CourseReview.authorName,
        // and loading the account of a deleted author threw, rolling the moderation back as 404.
        return review;
    }

    /**
     * Retires every review an account wrote, as part of deleting the account, and recomputes the
     * ratings they counted in. The review lists already left a deleted author's reviews out, but
     * the course rating went on counting them, and moderation could not touch them — so an
     * abusive review by an account deleted since skewed the rating for good. Soft-deleted like
     * the account itself, which keeps the rows for the audit trail.
     *
     * @return how many reviews were retired
     */
    @Transactional
    public int retireReviewsOf(UUID userId) {
        List<CourseReview> written = repo.findAllByUserId(userId);
        if (written.isEmpty()) return 0;
        // Keyed by id: the courses are lazy proxies, which do not compare equal reliably.
        Map<UUID, Course> rated = new HashMap<>();
        for (CourseReview review : written) {
            rated.put(review.getCourse().getId(), review.getCourse());
            repo.delete(review);   // soft-delete via @SQLDelete
        }
        repo.flush();   // the aggregate below must no longer see them
        rated.forEach(this::refreshCourseRating);
        return written.size();
    }

    private void refreshCourseRating(UUID courseId, Course course) {
        Object[] agg = repo.aggregateRating(courseId).get(0);
        Number avg = (Number) agg[0];
        Number cnt = (Number) agg[1];
        course.setRatingAvg(BigDecimal.valueOf(avg == null ? 0 : avg.doubleValue()).setScale(2, java.math.RoundingMode.HALF_UP));
        course.setRatingCount(cnt == null ? 0 : cnt.intValue());
        courses.save(course);
    }
}
