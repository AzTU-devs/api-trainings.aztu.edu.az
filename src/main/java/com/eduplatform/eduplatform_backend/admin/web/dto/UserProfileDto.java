package com.eduplatform.eduplatform_backend.admin.web.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Everything the platform holds about one account, for a super admin inspecting it: the account
 * itself, the expert profile if it has one, what it did as a participant, and its recent sign-in and
 * audit activity. Read-only, and deliberately without any secret: no password or refresh-token
 * hash, no one-time code, no OAuth token and no raw provider profile ever reaches this record.
 *
 * <p>Every property is always present; the ones that can be null are marked. Status-like values
 * are the stored codes as strings (the enums they come from are named on each). Lists are newest
 * first unless noted.
 *
 * @param expert null unless the account has an expert (tutor) profile, whatever its status
 */
public record UserProfileDto(Account account, Expert expert, Learner learner, Activity activity) {

    /**
     * @param status      UserStatus: ACTIVE, LOCKED, SUSPENDED or DELETED, as stored; a deleted
     *                    account keeps the status it had and is told apart by {@code deletedAt}
     * @param roles       RoleCode names, in the order USER, TUTOR, ADMIN, SUPER_ADMIN
     * @param avatarUrl   the authenticated {@code /api/media/{id}/content} path, or null
     * @param identities  the sign-in methods still linked, most recently linked first
     */
    public record Account(UUID id, String email, String phone, String firstName, String lastName,
                          String fullName, String finKod, String locale, String status, List<String> roles,
                          Instant emailVerifiedAt, Instant lastLoginAt, int failedLogins, Instant lockedUntil,
                          Instant createdAt, Instant updatedAt, Instant deletedAt, String avatarUrl,
                          List<Identity> identities) {}

    /** @param provider AuthProvider: LOCAL, GOOGLE, FACEBOOK or APPLE */
    public record Identity(String provider, String emailAtProvider, String displayName, boolean emailVerified,
                           Instant linkedAt, Instant lastLoginAt) {}

    /**
     * @param approvalStatus  TutorApprovalStatus: PENDING, APPROVED, REJECTED or SUSPENDED
     * @param expertise       the catalogue categories the expert picked, by name
     * @param customExpertise the areas the expert typed in themselves, in their order
     * @param courses         every course the expert teaches, as its editor or on its roster
     */
    public record Expert(UUID id, String displayName, String headline, String bio, Short yearsExperience,
                         String websiteUrl, String linkedinUrl, String academicTitle, String department,
                         String education, String certifications, String languages, String googleScholarUrl,
                         String researchGateUrl, String orcid, String githubUrl, String avatarUrl,
                         String approvalStatus, Instant approvedAt, String rejectionReason,
                         BigDecimal ratingAvg, int ratingCount, List<Category> expertise,
                         List<String> customExpertise, List<ApprovalStep> approvalHistory,
                         List<TaughtCourse> courses, List<RoomBooking> roomBookings, ExpertStats stats) {}

    public record Category(UUID id, String name) {}

    /** @param status ApprovalStatus: PENDING, APPROVED or REJECTED */
    public record ApprovalStep(UUID id, String status, String decisionNote, String decidedByName,
                               Instant decidedAt, Instant submittedAt) {}

    /**
     * @param courseType CourseType: ONLINE, OFFLINE or ONE_TIME
     * @param status     CourseStatus: DRAFT, IN_REVIEW, PUBLISHED, REJECTED or ARCHIVED
     * @param editor     whether this expert is the course's authorised editor, not only on its roster
     */
    public record TaughtCourse(UUID id, String slug, String title, String courseType, String status, boolean editor,
                               int enrolledCount, BigDecimal ratingAvg, int ratingCount, Instant publishedAt,
                               Instant createdAt) {}

    /** @param status BookingStatus: PENDING, APPROVED, REJECTED or CANCELLED */
    public record RoomBooking(UUID id, String roomName, Instant startsAt, Instant endsAt, String status,
                              BigDecimal totalFee, String currency) {}

    /**
     * @param totalEnrolled        the places counted on the expert's courses, summed
     * @param distinctParticipants the people holding those places, each counted once however many of
     *                             the expert's courses they are on
     */
    public record ExpertStats(int courseCount, int publishedCourseCount, long totalEnrolled,
                              long distinctParticipants) {}

    public record Learner(List<Enrollment> enrollments, List<Order> orders, List<Review> reviews,
                          LearnerStats stats) {}

    /**
     * @param status           EnrollmentStatus: PENDING_PAYMENT, ACTIVE, COMPLETED, CANCELLED or REFUNDED
     * @param source           EnrollmentSource: PURCHASE, FREE or ADMIN_GRANT
     * @param lessonsCompleted the course's live lessons this enrolment has completed; with
     *                         {@code lessonsTotal}, what {@code progressPercent} is worked out from
     * @param attendance       null for an online course; for an in-person one, the attendance
     *                         marked so far (possibly none)
     */
    public record Enrollment(UUID id, UUID courseId, String courseSlug, String courseTitle, String courseType,
                             String status, String source, int progressPercent, Instant enrolledAt,
                             Instant completedAt, Instant lastAccessedAt, long lessonsCompleted, long lessonsTotal,
                             Attendance attendance) {}

    /** @param byStatus AttendanceStatus name to count, only the statuses that occur */
    public record Attendance(long total, Map<String, Long> byStatus) {}

    public record Order(UUID id, String orderNumber, String status, BigDecimal subtotal, BigDecimal discount,
                        BigDecimal tax, BigDecimal total, String currency, Instant placedAt, Instant paidAt,
                        List<OrderItem> items, List<Payment> payments) {}

    /** In the order they were added to the order. */
    public record OrderItem(String itemType, String description, String courseTitle, int quantity,
                            BigDecimal unitPrice, BigDecimal totalPrice, String currency) {}

    public record Payment(String provider, String status, BigDecimal amount, String currency, String method,
                          Instant createdAt, String errorMessage) {}

    /**
     * @param visible whether the public can see the review: false once a moderator hid it, and false
     *                for a review retired with a deleted account, which is listed all the same
     */
    public record Review(UUID id, UUID courseId, String courseTitle, int rating, String title, String body,
                         boolean visible, Instant createdAt) {}

    /** @param averageProgress the mean progress over every enrolment listed, to one decimal; 0 with none */
    public record LearnerStats(int enrollmentCount, int activeCount, int completedCount,
                               BigDecimal averageProgress) {}

    /**
     * @param sessions       the newest 20 refresh-token sessions
     * @param securityEvents the newest 50 security events recorded against the account
     * @param auditTrail     the newest 50 audit entries where the account is the actor or the
     *                       USER entity acted on
     */
    public record Activity(List<Session> sessions, List<SecurityEvent> securityEvents,
                           List<AuditEntry> auditTrail) {}

    /** @param active not revoked and not expired, i.e. it can still be refreshed */
    public record Session(UUID id, Instant issuedAt, Instant expiresAt, Instant revokedAt, String revokeReason,
                          String ipAddress, String userAgent, boolean active) {}

    /** @param detail the event's recorded JSON detail, or null */
    public record SecurityEvent(UUID id, String eventType, String ipAddress, String userAgent, Object detail,
                                Instant occurredAt) {}

    public record AuditEntry(UUID id, String action, String entityType, UUID entityId, UUID actorId,
                             String actorRole, Instant occurredAt, String ipAddress) {}
}
