package com.eduplatform.eduplatform_backend.enrollment.service;

import com.eduplatform.eduplatform_backend.audit.service.AuditService;
import com.eduplatform.eduplatform_backend.common.enums.CourseStatus;
import com.eduplatform.eduplatform_backend.common.enums.EnrollmentSource;
import com.eduplatform.eduplatform_backend.common.enums.EnrollmentStatus;
import com.eduplatform.eduplatform_backend.common.enums.LessonProgressStatus;
import com.eduplatform.eduplatform_backend.common.error.Errors;
import com.eduplatform.eduplatform_backend.course.domain.Course;
import com.eduplatform.eduplatform_backend.course.domain.Lesson;
import com.eduplatform.eduplatform_backend.course.repo.CourseRepository;
import com.eduplatform.eduplatform_backend.course.repo.LessonRepository;
import com.eduplatform.eduplatform_backend.enrollment.domain.Enrollment;
import com.eduplatform.eduplatform_backend.enrollment.domain.LessonProgress;
import com.eduplatform.eduplatform_backend.enrollment.domain.LessonProgressId;
import com.eduplatform.eduplatform_backend.enrollment.repo.EnrollmentRepository;
import com.eduplatform.eduplatform_backend.enrollment.repo.LessonProgressRepository;
import com.eduplatform.eduplatform_backend.enrollment.web.dto.AddParticipantRequest;
import com.eduplatform.eduplatform_backend.enrollment.web.dto.CourseParticipantDto;
import com.eduplatform.eduplatform_backend.enrollment.web.dto.LessonProgressUpdateRequest;
import com.eduplatform.eduplatform_backend.identity.domain.User;
import com.eduplatform.eduplatform_backend.identity.repo.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class EnrollmentService {

    private final EnrollmentRepository enrollments;
    private final LessonProgressRepository progress;
    private final CourseRepository courses;
    private final LessonRepository lessons;
    private final UserRepository users;
    private final AuditService audit;

    /** Roster order: newest grant first, with the id breaking ties so a page never repeats a row. */
    private static final Sort NEWEST_GRANT_FIRST = Sort.by(Sort.Order.desc("enrolledAt"), Sort.Order.asc("id"));

    public EnrollmentService(EnrollmentRepository enrollments, LessonProgressRepository progress,
                             CourseRepository courses, LessonRepository lessons, UserRepository users,
                             AuditService audit) {
        this.enrollments = enrollments;
        this.progress = progress;
        this.courses = courses;
        this.lessons = lessons;
        this.users = users;
        this.audit = audit;
    }

    /** Free-tier or admin-grant enrollment. Paid enrollments are created by the payment flow. */
    @Transactional
    public Enrollment enroll(UUID userId, UUID courseId, EnrollmentSource source) {
        Course course = requireCourse(courseId);
        if (course.getStatus() != CourseStatus.PUBLISHED) {
            throw Errors.conflict("COURSE_NOT_PUBLISHED", "Cannot enrol in a non-published course");
        }
        if (enrollments.existsByUserIdAndCourseId(userId, courseId)) {
            throw Errors.conflict("ALREADY_ENROLLED", "You are already enrolled in this course");
        }
        // Only the FREE route is gated on price. An ADMIN_GRANT is meant to bypass payment, and a
        // PURCHASE enrollment is created once the payment flow has taken the money.
        if (source == EnrollmentSource.FREE && !course.isFree()) {
            throw Errors.unprocessable("PAYMENT_REQUIRED",
                    "This course is paid; enrol via the checkout flow instead");
        }
        User user = users.findById(userId)
                .orElseThrow(() -> Errors.notFound("USER_NOT_FOUND", "User does not exist"));

        Enrollment e = Enrollment.builder()
                .user(user)
                .course(course)
                .status(EnrollmentStatus.ACTIVE)
                .source(source)
                .enrolledAt(Instant.now())
                .build();
        e.setId(UUID.randomUUID());
        e = enrollments.save(e);

        courses.incrementEnrolledCount(courseId);
        return e;
    }

    @Transactional(readOnly = true)
    public Page<Enrollment> mine(UUID userId, Pageable pageable) {
        return enrollments.findAllByUserId(userId, pageable);
    }

    /** Saved per-lesson progress for the current user's enrollment in a course. */
    @Transactional(readOnly = true)
    public java.util.List<LessonProgress> courseProgress(UUID userId, UUID courseId) {
        Enrollment e = enrollments.findByUserIdAndCourseId(userId, courseId)
                .orElseThrow(() -> Errors.notFound("ENROLLMENT_NOT_FOUND", "You are not enrolled in this course"));
        return progress.findAllByEnrollmentId(e.getId());
    }

    @Transactional
    public LessonProgress updateProgress(UUID userId, UUID courseId, UUID lessonId,
                                         LessonProgressUpdateRequest req) {
        Enrollment e = enrollments.findByUserIdAndCourseId(userId, courseId)
                .orElseThrow(() -> Errors.forbidden("NOT_ENROLLED", "You are not enrolled in this course"));
        Lesson lesson = lessons.findById(lessonId)
                .orElseThrow(() -> Errors.notFound("LESSON_NOT_FOUND", "Lesson does not exist"));
        if (!lesson.getModule().getCourse().getId().equals(courseId)) {
            throw Errors.badRequest("LESSON_COURSE_MISMATCH", "Lesson does not belong to this course");
        }

        LessonProgressId pk = new LessonProgressId(e.getId(), lessonId);
        LessonProgress lp = progress.findById(pk).orElseGet(() -> {
            LessonProgress fresh = LessonProgress.builder()
                    .id(pk).enrollment(e).lesson(lesson)
                    .status(LessonProgressStatus.NOT_STARTED)
                    .positionSec(0)
                    .updatedAt(Instant.now())
                    .build();
            return progress.save(fresh);
        });
        lp.setStatus(req.status());
        lp.setPositionSec(req.positionSec());
        if (req.status() == LessonProgressStatus.COMPLETED && lp.getCompletedAt() == null) {
            lp.setCompletedAt(Instant.now());
        }
        progress.save(lp);

        // Recompute coarse progress %
        long total = lessons.countByCourseId(courseId);
        long completed = progress.countCompletedByEnrollment(e.getId());
        short pct = total == 0 ? 0 : (short) Math.round(100.0 * completed / total);
        e.setProgressPercent(pct);
        e.setLastAccessedAt(Instant.now());
        if (pct == 100 && e.getCompletedAt() == null) {
            e.setCompletedAt(Instant.now());
            e.setStatus(EnrollmentStatus.COMPLETED);
        }
        enrollments.save(e);
        return lp;
    }

    // ---------- administrative grants ----------

    /**
     * A page of a course's roster for the dashboard's participants screen.
     *
     * @param status null for every status, so that a place revoked earlier stays visible as a
     *               CANCELLED row rather than vanishing from the admin's view
     */
    @Transactional(readOnly = true)
    public Page<CourseParticipantDto> listParticipants(UUID courseId, EnrollmentStatus status, Pageable pageable) {
        requireCourse(courseId);
        Pageable ordered = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), NEWEST_GRANT_FIRST);
        Page<Enrollment> page = status == null
                ? enrollments.findAllByCourseId(courseId, ordered)
                : enrollments.findAllByCourseIdAndStatus(courseId, status, ordered);
        // Mapped here rather than in the controller: the transaction is what keeps the joined
        // user readable, open-in-view being off.
        return page.map(EnrollmentService::toParticipantDto);
    }

    /**
     * Places somebody on a course by administrative grant.
     *
     * <p>Deliberately skips the price and publication rules {@link #enroll} applies: seating a
     * participant by hand is the sanctioned way into a paid course, and into one that is not in
     * the catalogue yet. Granting a place somebody already holds changes nothing and still
     * succeeds, so the add-by-email form is safe to submit twice.
     */
    @Transactional
    public CourseParticipantDto addParticipant(UUID courseId, AddParticipantRequest req) {
        Course course = requireCourse(courseId);
        User user = resolveParticipant(req);

        Enrollment e = enrollments.findByUserIdAndCourseId(user.getId(), courseId).orElse(null);
        if (e != null && holdsAPlace(e.getStatus())) {
            return toParticipantDto(e);
        }
        EnrollmentStatus before = e == null ? null : e.getStatus();
        if (e == null) {
            e = Enrollment.builder()
                    .user(user)
                    .course(course)
                    .status(EnrollmentStatus.ACTIVE)
                    .source(EnrollmentSource.ADMIN_GRANT)
                    .enrolledAt(Instant.now())
                    .build();
            e.setId(UUID.randomUUID());
        } else {
            // A cancelled or refunded enrolment is reinstated, never replaced: one row per
            // (user, course) is all the unique constraint allows, and the lesson progress and
            // attendance hanging off this one must survive the round trip.
            e.setStatus(EnrollmentStatus.ACTIVE);
            e.setSource(EnrollmentSource.ADMIN_GRANT);
            e.setEnrolledAt(Instant.now());
        }
        e = enrollments.save(e);
        courses.incrementEnrolledCount(courseId);

        audit.record(AuditService.Actions.CREATE, "ENROLLMENT", e.getId(),
                before == null ? null : AuditService.snapshot("status", before.name()),
                AuditService.snapshot("status", e.getStatus().name(), "source", e.getSource().name(),
                        "courseId", courseId.toString(), "userId", user.getId().toString()));
        return toParticipantDto(e);
    }

    /**
     * Revokes a granted place. The enrolment is cancelled rather than deleted, because the
     * lesson progress, attendance and certificates behind it — and the audit trail pointing at
     * it — have to outlive the removal.
     */
    @Transactional
    public void removeParticipant(UUID courseId, UUID userId) {
        requireCourse(courseId);
        Enrollment e = enrollments.findByUserIdAndCourseId(userId, courseId)
                .orElseThrow(() -> Errors.notFound("ENROLLMENT_NOT_FOUND",
                        "This user is not enrolled in this course"));
        if (e.getStatus() == EnrollmentStatus.CANCELLED) {
            return;
        }
        EnrollmentStatus before = e.getStatus();
        e.setStatus(EnrollmentStatus.CANCELLED);
        enrollments.save(e);
        if (holdsAPlace(before)) {
            // Only a place that was counted is handed back; a PENDING_PAYMENT or REFUNDED row
            // never added to the tally, and decrementing it would under-report the course.
            courses.decrementEnrolledCount(courseId);
        }

        audit.record(AuditService.Actions.DELETE, "ENROLLMENT", e.getId(),
                AuditService.snapshot("status", before.name()),
                AuditService.snapshot("status", EnrollmentStatus.CANCELLED.name(),
                        "courseId", courseId.toString(), "userId", userId.toString()));
    }

    // ---------- helpers ----------

    private Course requireCourse(UUID courseId) {
        return courses.findById(courseId)
                .orElseThrow(() -> Errors.notFound("COURSE_NOT_FOUND", "Course does not exist"));
    }

    /** The account a grant points at: by id when the dashboard has one, else by the typed email. */
    private User resolveParticipant(AddParticipantRequest req) {
        if (req.userId() != null) {
            return users.findById(req.userId())
                    .orElseThrow(() -> Errors.notFound("USER_NOT_FOUND", "User does not exist"));
        }
        String email = req.email() == null ? "" : req.email().trim();
        if (email.isEmpty()) {
            throw Errors.badRequest("PARTICIPANT_IDENTIFIER_REQUIRED",
                    "Identify the participant by userId or email");
        }
        return users.findByEmailIgnoreCase(email)
                // Its own code, not USER_NOT_FOUND: an email nobody has registered is the one
                // case the dashboard answers by offering to create the account.
                .orElseThrow(() -> Errors.notFound("PARTICIPANT_EMAIL_NOT_REGISTERED",
                        "No account is registered with this email"));
    }

    /** The statuses that occupy a place on the course, and so are counted in enrolledCount. */
    private static boolean holdsAPlace(EnrollmentStatus status) {
        return status == EnrollmentStatus.ACTIVE || status == EnrollmentStatus.COMPLETED;
    }

    private static CourseParticipantDto toParticipantDto(Enrollment e) {
        User u = e.getUser();
        // Reading the id off the lazy avatar proxy does not load the row.
        UUID avatarId = u.getAvatar() == null ? null : u.getAvatar().getId();
        return new CourseParticipantDto(
                e.getId(),
                u.getId(),
                (u.getFirstName() + " " + (u.getLastName() == null ? "" : u.getLastName())).trim(),
                u.getEmail(),
                avatarId == null ? null : "/api/media/" + avatarId + "/content",
                e.getStatus(),
                e.getSource(),
                e.getEnrolledAt(),
                e.getCompletedAt(),
                e.getProgressPercent(),
                e.getLastAccessedAt());
    }
}
