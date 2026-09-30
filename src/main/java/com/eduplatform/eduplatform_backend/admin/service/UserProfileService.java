package com.eduplatform.eduplatform_backend.admin.service;

import com.eduplatform.eduplatform_backend.admin.repo.UserProfileQueries;
import com.eduplatform.eduplatform_backend.admin.repo.UserProfileQueries.AccountRow;
import com.eduplatform.eduplatform_backend.admin.repo.UserProfileQueries.EnrollmentRow;
import com.eduplatform.eduplatform_backend.admin.repo.UserProfileQueries.ExpertRow;
import com.eduplatform.eduplatform_backend.admin.repo.UserProfileQueries.OrderRow;
import com.eduplatform.eduplatform_backend.admin.web.dto.UserProfileDto;
import com.eduplatform.eduplatform_backend.common.enums.AttendanceStatus;
import com.eduplatform.eduplatform_backend.common.enums.CourseStatus;
import com.eduplatform.eduplatform_backend.common.enums.CourseType;
import com.eduplatform.eduplatform_backend.common.enums.EnrollmentStatus;
import com.eduplatform.eduplatform_backend.common.enums.RoleCode;
import com.eduplatform.eduplatform_backend.common.error.Errors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A super admin's deep look at one account: who it is, what it teaches if it is an expert, what it
 * did as a participant, and how it has been signing in. For inspection, so a deleted account is
 * shown too, with its deletion date, rather than answered as unknown.
 *
 * <p>Read-only, in one repeatable-read transaction: the dozen-odd queries then see the same moment,
 * so the counts in the stats agree with the lists they sit next to even while the account is in use.
 */
@Service
public class UserProfileService {

    static final int SESSION_LIMIT = 20;
    static final int SECURITY_EVENT_LIMIT = 50;
    static final int AUDIT_LIMIT = 50;

    private final UserProfileQueries queries;

    public UserProfileService(UserProfileQueries queries) {
        this.queries = queries;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public UserProfileDto profile(UUID userId) {
        AccountRow account = queries.account(userId)
                .orElseThrow(() -> Errors.notFound("USER_NOT_FOUND", "User does not exist"));
        return new UserProfileDto(
                account(account),
                queries.expert(userId).map(this::expert).orElse(null),
                learner(userId),
                new UserProfileDto.Activity(
                        queries.sessions(userId, SESSION_LIMIT),
                        queries.securityEvents(userId, SECURITY_EVENT_LIMIT),
                        queries.auditTrail(userId, AUDIT_LIMIT)));
    }

    private UserProfileDto.Account account(AccountRow a) {
        // A fixed order, lowest to highest, so the same account always lists its roles the same way.
        List<String> roles = queries.roles(a.id()).stream()
                .sorted(Comparator.comparingInt(UserProfileService::roleRank).thenComparing(Comparator.naturalOrder()))
                .toList();
        return new UserProfileDto.Account(
                a.id(), a.email(), a.phone(), a.firstName(), a.lastName(),
                (a.firstName() + " " + (a.lastName() == null ? "" : a.lastName())).trim(),
                a.finKod(), a.locale(), a.status(), roles, a.emailVerifiedAt(), a.lastLoginAt(), a.failedLogins(),
                a.lockedUntil(), a.createdAt(), a.updatedAt(), a.deletedAt(), mediaUrl(a.avatarMediaId()),
                queries.identities(a.id()));
    }

    private UserProfileDto.Expert expert(ExpertRow t) {
        List<UserProfileDto.TaughtCourse> courses = queries.taughtCourses(t.id());
        UserProfileDto.ExpertStats stats = new UserProfileDto.ExpertStats(
                courses.size(),
                (int) courses.stream().filter(c -> CourseStatus.PUBLISHED.name().equals(c.status())).count(),
                courses.stream().mapToLong(UserProfileDto.TaughtCourse::enrolledCount).sum(),
                queries.distinctParticipants(t.id()));
        return new UserProfileDto.Expert(
                t.id(), t.displayName(), t.headline(), t.bio(), t.yearsExperience(), t.websiteUrl(),
                t.linkedinUrl(), t.academicTitle(), t.department(), t.education(), t.certifications(),
                t.languages(), t.googleScholarUrl(), t.researchGateUrl(), t.orcid(), t.githubUrl(),
                mediaUrl(t.avatarMediaId()), t.approvalStatus(), t.approvedAt(), t.rejectionReason(),
                t.ratingAvg(), t.ratingCount(), queries.expertise(t.id()), t.customExpertise(),
                queries.approvalHistory(t.id()), courses, queries.roomBookings(t.id()), stats);
    }

    private UserProfileDto.Learner learner(UUID userId) {
        Map<UUID, Map<String, Long>> marks = queries.attendance(userId);
        List<UserProfileDto.Enrollment> enrollments = queries.enrollments(userId).stream()
                .map(e -> enrollment(e, marks.get(e.id())))
                .toList();

        Map<UUID, List<UserProfileDto.OrderItem>> items = queries.orderItems(userId);
        Map<UUID, List<UserProfileDto.Payment>> payments = queries.payments(userId);
        List<UserProfileDto.Order> orders = queries.orders(userId).stream()
                .map(o -> order(o, items.getOrDefault(o.id(), List.of()), payments.getOrDefault(o.id(), List.of())))
                .toList();

        return new UserProfileDto.Learner(enrollments, orders, queries.reviews(userId), learnerStats(enrollments));
    }

    /**
     * Attendance only means something on an in-person course, so an online enrolment reports null
     * rather than a zero that reads as "never came".
     */
    private static UserProfileDto.Enrollment enrollment(EnrollmentRow e, Map<String, Long> marks) {
        UserProfileDto.Attendance attendance = null;
        if (CourseType.valueOf(e.courseType()).isInPerson()) {
            Map<String, Long> byStatus = new LinkedHashMap<>();
            long total = 0;
            for (AttendanceStatus status : AttendanceStatus.values()) {
                Long count = marks == null ? null : marks.get(status.name());
                if (count != null) {
                    byStatus.put(status.name(), count);
                    total += count;
                }
            }
            attendance = new UserProfileDto.Attendance(total, byStatus);
        }
        return new UserProfileDto.Enrollment(
                e.id(), e.courseId(), e.courseSlug(), e.courseTitle(), e.courseType(), e.status(), e.source(),
                e.progressPercent(), e.enrolledAt(), e.completedAt(), e.lastAccessedAt(), e.lessonsCompleted(),
                e.lessonsTotal(), attendance);
    }

    private static UserProfileDto.Order order(OrderRow o, List<UserProfileDto.OrderItem> items,
                                              List<UserProfileDto.Payment> payments) {
        return new UserProfileDto.Order(o.id(), o.orderNumber(), o.status(), o.subtotal(), o.discount(), o.tax(),
                o.total(), o.currency(), o.placedAt(), o.paidAt(), items, payments);
    }

    private static UserProfileDto.LearnerStats learnerStats(List<UserProfileDto.Enrollment> enrollments) {
        int active = (int) enrollments.stream().filter(e -> EnrollmentStatus.ACTIVE.name().equals(e.status())).count();
        int completed = (int) enrollments.stream()
                .filter(e -> EnrollmentStatus.COMPLETED.name().equals(e.status())).count();
        BigDecimal average = enrollments.isEmpty()
                ? BigDecimal.ZERO.setScale(1)
                : BigDecimal.valueOf(enrollments.stream().mapToLong(UserProfileDto.Enrollment::progressPercent).sum())
                        .divide(BigDecimal.valueOf(enrollments.size()), 1, RoundingMode.HALF_UP);
        return new UserProfileDto.LearnerStats(enrollments.size(), active, completed, average);
    }

    /**
     * The authenticated media path, as the dashboard's other admin views use for account photos: a
     * super admin may read any file, and a pending expert's portrait is not public yet.
     */
    private static String mediaUrl(UUID mediaId) {
        return mediaId == null ? null : "/api/media/" + mediaId + "/content";
    }

    private static int roleRank(String code) {
        try {
            return RoleCode.valueOf(code).ordinal();
        } catch (IllegalArgumentException unknown) {
            return RoleCode.values().length;
        }
    }
}
