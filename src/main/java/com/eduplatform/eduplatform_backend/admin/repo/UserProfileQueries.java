package com.eduplatform.eduplatform_backend.admin.repo;

import com.eduplatform.eduplatform_backend.admin.web.dto.UserProfileDto;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The SQL behind the super admin's inspection of one account (UserProfileService): one statement
 * per part of the profile, so a profile costs the same dozen-odd queries however much the account
 * holds, and none of them runs once per row.
 *
 * <p>Plain SQL rather than the entities, for two reasons. The view exists to see an account in
 * whatever state it is, deleted ones included, and the entities involved carry soft-delete
 * restrictions that would hide the deleted account itself, the expert profile retired with it and
 * the reviews retired with it; here each query says for itself which deleted rows it wants. And it
 * reads a dozen tables across the modules for a few columns each, which through the entities would
 * mean loading and walking lazy graphs to throw most of them away.
 *
 * <p>Only the columns the view shows are selected, which is also what keeps secrets out of it: no
 * statement here reads a password or token hash, a one-time code, a provider token or a raw
 * provider profile or payment payload.
 */
@Repository
public class UserProfileQueries {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public UserProfileQueries(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** The users row, deleted or not. */
    public record AccountRow(UUID id, String email, String phone, String firstName, String lastName,
                             String finKod, String locale, String status, Instant emailVerifiedAt,
                             Instant lastLoginAt, int failedLogins, Instant lockedUntil, Instant createdAt,
                             Instant updatedAt, Instant deletedAt, UUID avatarMediaId) {}

    public Optional<AccountRow> account(UUID userId) {
        return jdbc.query("""
                select u.id, u.email, u.phone, u.first_name, u.last_name, u.fin_kod, u.locale, u.status,
                       u.email_verified_at, u.last_login_at, u.failed_logins, u.locked_until,
                       u.created_at, u.updated_at, u.deleted_at, u.avatar_media_id
                  from users u
                 where u.id = ?
                """, (rs, i) -> new AccountRow(
                        uuid(rs, "id"), rs.getString("email"), rs.getString("phone"),
                        rs.getString("first_name"), rs.getString("last_name"), rs.getString("fin_kod"),
                        rs.getString("locale"), rs.getString("status"), instant(rs, "email_verified_at"),
                        instant(rs, "last_login_at"), rs.getInt("failed_logins"), instant(rs, "locked_until"),
                        instant(rs, "created_at"), instant(rs, "updated_at"), instant(rs, "deleted_at"),
                        uuid(rs, "avatar_media_id")),
                userId).stream().findFirst();
    }

    /** Role codes, unordered; the caller puts them in a fixed order. */
    public List<String> roles(UUID userId) {
        return jdbc.queryForList("""
                select r.code from user_roles ur join roles r on r.id = ur.role_id where ur.user_id = ?
                """, String.class, userId);
    }

    /** The sign-in methods still linked; an unlinked one is soft-deleted and no longer one. */
    public List<UserProfileDto.Identity> identities(UUID userId) {
        return jdbc.query("""
                select provider, email_at_provider, display_name, email_verified, linked_at, last_login_at
                  from user_identities
                 where user_id = ? and deleted_at is null
                 order by linked_at desc, id desc
                """, (rs, i) -> new UserProfileDto.Identity(
                        rs.getString("provider"), rs.getString("email_at_provider"), rs.getString("display_name"),
                        rs.getBoolean("email_verified"), instant(rs, "linked_at"), instant(rs, "last_login_at")),
                userId);
    }

    /** The expert profile without its lists; a profile retired with a deleted account is included. */
    public record ExpertRow(UUID id, String displayName, String headline, String bio, Short yearsExperience,
                            String websiteUrl, String linkedinUrl, String academicTitle, String department,
                            String education, String certifications, String languages,
                            String googleScholarUrl, String researchGateUrl, String orcid, String githubUrl,
                            UUID avatarMediaId, String approvalStatus, Instant approvedAt,
                            String rejectionReason, BigDecimal ratingAvg, int ratingCount,
                            List<String> customExpertise) {}

    /** At most one row: tutor_profiles.user_id is unique, deleted rows included. */
    public Optional<ExpertRow> expert(UUID userId) {
        return jdbc.query("""
                select t.id, concat(u.first_name, ' ', u.last_name) as display_name, t.headline, t.bio,
                       t.years_experience, t.website_url, t.linkedin_url, t.academic_title, t.department,
                       t.education, t.certifications, t.languages, t.google_scholar_url, t.research_gate_url,
                       t.orcid, t.github_url, t.avatar_media_id, t.approval_status, t.approved_at,
                       t.rejection_reason, t.rating_avg, t.rating_count, t.custom_expertise::text as custom_expertise
                  from tutor_profiles t
                  join users u on u.id = t.user_id
                 where t.user_id = ?
                """, (rs, i) -> {
                    // wasNull() reports on the last column read, so it is asked right here.
                    short yearsRead = rs.getShort("years_experience");
                    Short years = rs.wasNull() ? null : yearsRead;
                    return new ExpertRow(
                            uuid(rs, "id"), rs.getString("display_name"), rs.getString("headline"),
                            rs.getString("bio"), years, rs.getString("website_url"),
                            rs.getString("linkedin_url"), rs.getString("academic_title"), rs.getString("department"),
                            rs.getString("education"), rs.getString("certifications"), rs.getString("languages"),
                            rs.getString("google_scholar_url"), rs.getString("research_gate_url"),
                            rs.getString("orcid"), rs.getString("github_url"), uuid(rs, "avatar_media_id"),
                            rs.getString("approval_status"), instant(rs, "approved_at"),
                            rs.getString("rejection_reason"), rs.getBigDecimal("rating_avg"),
                            rs.getInt("rating_count"), stringList(rs.getString("custom_expertise")));
                }, userId).stream().findFirst();
    }

    public List<UserProfileDto.Category> expertise(UUID tutorId) {
        return jdbc.query("""
                select c.id, c.name
                  from tutor_expertises te
                  join categories c on c.id = te.category_id
                 where te.tutor_id = ?
                 order by c.name, c.id
                """, (rs, i) -> new UserProfileDto.Category(uuid(rs, "id"), rs.getString("name")), tutorId);
    }

    public List<UserProfileDto.ApprovalStep> approvalHistory(UUID tutorId) {
        return jdbc.query("""
                select a.id, a.status, a.decision_note,
                       case when d.id is null then null else concat(d.first_name, ' ', d.last_name) end
                           as decided_by_name,
                       a.decided_at, a.submitted_at
                  from tutor_approval_requests a
                  left join users d on d.id = a.decided_by
                 where a.tutor_id = ?
                 order by a.submitted_at desc, a.created_at desc, a.id desc
                """, (rs, i) -> new UserProfileDto.ApprovalStep(
                        uuid(rs, "id"), rs.getString("status"), rs.getString("decision_note"),
                        rs.getString("decided_by_name"), instant(rs, "decided_at"), instant(rs, "submitted_at")),
                tutorId);
    }

    /** Live courses the expert teaches, as the authorised editor or on the roster. */
    public List<UserProfileDto.TaughtCourse> taughtCourses(UUID tutorId) {
        return jdbc.query("""
                select c.id, c.slug, c.title, c.course_type, c.status, c.tutor_id = ? as editor,
                       c.enrolled_count, c.rating_avg, c.rating_count, c.published_at, c.created_at
                  from courses c
                 where c.deleted_at is null
                   and (c.tutor_id = ?
                        or exists (select 1 from course_tutors ct where ct.course_id = c.id and ct.tutor_id = ?))
                 order by c.created_at desc, c.id desc
                """, (rs, i) -> new UserProfileDto.TaughtCourse(
                        uuid(rs, "id"), rs.getString("slug"), rs.getString("title"), rs.getString("course_type"),
                        rs.getString("status"), rs.getBoolean("editor"), rs.getInt("enrolled_count"),
                        rs.getBigDecimal("rating_avg"), rs.getInt("rating_count"), instant(rs, "published_at"),
                        instant(rs, "created_at")),
                tutorId, tutorId, tutorId);
    }

    /** Newest session first; the room's name is read even if the room was deleted since. */
    public List<UserProfileDto.RoomBooking> roomBookings(UUID tutorId) {
        return jdbc.query("""
                select b.id, r.name as room_name, b.starts_at, b.ends_at, b.status, b.total_fee, b.currency
                  from room_bookings b
                  left join rooms r on r.id = b.room_id
                 where b.tutor_id = ? and b.deleted_at is null
                 order by b.starts_at desc, b.id desc
                """, (rs, i) -> new UserProfileDto.RoomBooking(
                        uuid(rs, "id"), rs.getString("room_name"), instant(rs, "starts_at"),
                        instant(rs, "ends_at"), rs.getString("status"), rs.getBigDecimal("total_fee"),
                        rs.getString("currency")),
                tutorId);
    }

    /**
     * The people holding a place (ACTIVE or COMPLETED, live account) on any of the expert's live
     * courses, each counted once — the same places the courses' enrolled counts are made of.
     */
    public long distinctParticipants(UUID tutorId) {
        Long count = jdbc.queryForObject("""
                select count(distinct e.user_id)
                  from enrollments e
                  join courses c on c.id = e.course_id and c.deleted_at is null
                  join users u on u.id = e.user_id and u.deleted_at is null
                 where e.deleted_at is null
                   and e.status in ('ACTIVE', 'COMPLETED')
                   and (c.tutor_id = ?
                        or exists (select 1 from course_tutors ct where ct.course_id = c.id and ct.tutor_id = ?))
                """, Long.class, tutorId, tutorId);
        return count == null ? 0 : count;
    }

    /** An enrolment without its attendance, which {@link #attendance} brings in one query. */
    public record EnrollmentRow(UUID id, UUID courseId, String courseSlug, String courseTitle, String courseType,
                                String status, String source, int progressPercent, Instant enrolledAt,
                                Instant completedAt, Instant lastAccessedAt, long lessonsCompleted,
                                long lessonsTotal) {}

    /**
     * Every enrolment, in any status and in any course, a deleted one included, with the lesson
     * counts behind its progress: the course's live lessons and the ones this enrolment completed,
     * counted as EnrollmentService counts them when it works out the percentage.
     */
    public List<EnrollmentRow> enrollments(UUID userId) {
        return jdbc.query("""
                select e.id, e.course_id, c.slug as course_slug, c.title as course_title, c.course_type,
                       e.status, e.source, e.progress_percent, e.enrolled_at, e.completed_at, e.last_accessed_at,
                       (select count(*)
                          from lesson_progress lp
                          join lessons l on l.id = lp.lesson_id and l.deleted_at is null
                          join course_modules m on m.id = l.module_id and m.deleted_at is null
                         where lp.enrollment_id = e.id and m.course_id = e.course_id
                           and lp.status = 'COMPLETED') as lessons_completed,
                       (select count(*)
                          from lessons l
                          join course_modules m on m.id = l.module_id and m.deleted_at is null
                         where m.course_id = e.course_id and l.deleted_at is null) as lessons_total
                  from enrollments e
                  join courses c on c.id = e.course_id
                 where e.user_id = ? and e.deleted_at is null
                 order by e.enrolled_at desc, e.id desc
                """, (rs, i) -> new EnrollmentRow(
                        uuid(rs, "id"), uuid(rs, "course_id"), rs.getString("course_slug"),
                        rs.getString("course_title"), rs.getString("course_type"), rs.getString("status"),
                        rs.getString("source"), rs.getInt("progress_percent"), instant(rs, "enrolled_at"),
                        instant(rs, "completed_at"), instant(rs, "last_accessed_at"),
                        rs.getLong("lessons_completed"), rs.getLong("lessons_total")),
                userId);
    }

    /** Attendance marks per enrolment and status, for every enrolment of the account at once. */
    public Map<UUID, Map<String, Long>> attendance(UUID userId) {
        Map<UUID, Map<String, Long>> out = new HashMap<>();
        jdbc.query("""
                select a.enrollment_id, a.status, count(*) as marks
                  from attendance_records a
                  join enrollments e on e.id = a.enrollment_id
                 where e.user_id = ? and e.deleted_at is null
                 group by a.enrollment_id, a.status
                """, rs -> {
                    out.computeIfAbsent(uuid(rs, "enrollment_id"), k -> new HashMap<>())
                            .put(rs.getString("status"), rs.getLong("marks"));
                }, userId);
        return out;
    }

    /** An order without its items and payments, which come in one query each. */
    public record OrderRow(UUID id, String orderNumber, String status, BigDecimal subtotal, BigDecimal discount,
                           BigDecimal tax, BigDecimal total, String currency, Instant placedAt, Instant paidAt) {}

    public List<OrderRow> orders(UUID userId) {
        return jdbc.query("""
                select o.id, o.order_number, o.status, o.subtotal, o.discount, o.tax, o.total, o.currency,
                       o.placed_at, o.paid_at
                  from orders o
                 where o.user_id = ? and o.deleted_at is null
                 order by o.placed_at desc, o.id desc
                """, (rs, i) -> new OrderRow(
                        uuid(rs, "id"), rs.getString("order_number"), rs.getString("status"),
                        rs.getBigDecimal("subtotal"), rs.getBigDecimal("discount"), rs.getBigDecimal("tax"),
                        rs.getBigDecimal("total"), rs.getString("currency"), instant(rs, "placed_at"),
                        instant(rs, "paid_at")),
                userId);
    }

    /** Every order's items, grouped by order, each group in the order the items were added. */
    public Map<UUID, List<UserProfileDto.OrderItem>> orderItems(UUID userId) {
        Map<UUID, List<UserProfileDto.OrderItem>> out = new LinkedHashMap<>();
        jdbc.query("""
                select i.order_id, i.item_type, i.description, c.title as course_title, i.quantity,
                       i.unit_price, i.total_price, i.currency
                  from order_items i
                  join orders o on o.id = i.order_id
                  left join courses c on c.id = i.course_id
                 where o.user_id = ? and o.deleted_at is null
                 order by i.created_at, i.id
                """, rs -> {
                    out.computeIfAbsent(uuid(rs, "order_id"), k -> new ArrayList<>()).add(new UserProfileDto.OrderItem(
                            rs.getString("item_type"), rs.getString("description"), rs.getString("course_title"),
                            rs.getInt("quantity"), rs.getBigDecimal("unit_price"), rs.getBigDecimal("total_price"),
                            rs.getString("currency")));
                }, userId);
        return out;
    }

    /** Every order's payment attempts, grouped by order, newest first. */
    public Map<UUID, List<UserProfileDto.Payment>> payments(UUID userId) {
        Map<UUID, List<UserProfileDto.Payment>> out = new LinkedHashMap<>();
        jdbc.query("""
                select p.order_id, p.provider, p.status, p.amount, p.currency, p.method, p.created_at,
                       p.error_message
                  from payments p
                  join orders o on o.id = p.order_id
                 where o.user_id = ? and o.deleted_at is null
                 order by p.created_at desc, p.id desc
                """, rs -> {
                    out.computeIfAbsent(uuid(rs, "order_id"), k -> new ArrayList<>()).add(new UserProfileDto.Payment(
                            rs.getString("provider"), rs.getString("status"), rs.getBigDecimal("amount"),
                            rs.getString("currency"), rs.getString("method"), instant(rs, "created_at"),
                            rs.getString("error_message")));
                }, userId);
        return out;
    }

    /**
     * The account's reviews, including the ones retired with a deleted account, which are reported
     * as not visible — they are not, and the inspection is the one place they are still worth seeing.
     */
    public List<UserProfileDto.Review> reviews(UUID userId) {
        return jdbc.query("""
                select r.id, r.course_id, c.title as course_title, r.rating, r.title, r.body,
                       (r.is_visible and r.deleted_at is null) as visible, r.created_at
                  from course_reviews r
                  join courses c on c.id = r.course_id
                 where r.user_id = ?
                 order by r.created_at desc, r.id desc
                """, (rs, i) -> new UserProfileDto.Review(
                        uuid(rs, "id"), uuid(rs, "course_id"), rs.getString("course_title"), rs.getInt("rating"),
                        rs.getString("title"), rs.getString("body"), rs.getBoolean("visible"),
                        instant(rs, "created_at")),
                userId);
    }

    /** The newest refresh-token sessions; the token hash is never read. */
    public List<UserProfileDto.Session> sessions(UUID userId, int limit) {
        return jdbc.query("""
                select id, issued_at, expires_at, revoked_at, revoke_reason, host(ip_address) as ip_address,
                       user_agent, (revoked_at is null and expires_at > now()) as active
                  from refresh_tokens
                 where user_id = ?
                 order by issued_at desc, id desc
                 limit ?
                """, (rs, i) -> new UserProfileDto.Session(
                        uuid(rs, "id"), instant(rs, "issued_at"), instant(rs, "expires_at"),
                        instant(rs, "revoked_at"), rs.getString("revoke_reason"), rs.getString("ip_address"),
                        rs.getString("user_agent"), rs.getBoolean("active")),
                userId, limit);
    }

    public List<UserProfileDto.SecurityEvent> securityEvents(UUID userId, int limit) {
        return jdbc.query("""
                select id, event_type, host(ip_address) as ip_address, user_agent, detail::text as detail,
                       occurred_at
                  from security_events
                 where user_id = ?
                 order by occurred_at desc, id desc
                 limit ?
                """, (rs, i) -> new UserProfileDto.SecurityEvent(
                        uuid(rs, "id"), rs.getString("event_type"), rs.getString("ip_address"),
                        rs.getString("user_agent"), jsonValue(rs.getString("detail")), instant(rs, "occurred_at")),
                userId, limit);
    }

    /**
     * The newest audit entries the account made, or that were made on it as a USER. Two index-ordered
     * branches (idx_audit_actor and idx_audit_entity), each already cut to the limit, then merged; a
     * row in both, such as an account editing itself, is one row after the UNION.
     */
    public List<UserProfileDto.AuditEntry> auditTrail(UUID userId, int limit) {
        return jdbc.query("""
                select id, action, entity_type, entity_id, actor_id, actor_role, occurred_at, ip_address
                  from ((select id, action, entity_type, entity_id, actor_id, actor_role, occurred_at,
                                host(ip_address) as ip_address
                           from audit_logs
                          where actor_id = ?
                          order by occurred_at desc
                          limit ?)
                        union
                        (select id, action, entity_type, entity_id, actor_id, actor_role, occurred_at,
                                host(ip_address) as ip_address
                           from audit_logs
                          where entity_type = 'USER' and entity_id = ?
                          order by occurred_at desc
                          limit ?)) trail
                 order by occurred_at desc, id desc
                 limit ?
                """, (rs, i) -> new UserProfileDto.AuditEntry(
                        uuid(rs, "id"), rs.getString("action"), rs.getString("entity_type"), uuid(rs, "entity_id"),
                        uuid(rs, "actor_id"), rs.getString("actor_role"), instant(rs, "occurred_at"),
                        rs.getString("ip_address")),
                userId, limit, userId, limit, limit);
    }

    // ── column readers ───────────────────────────────────────────────────

    private static UUID uuid(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, UUID.class);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private List<String> stringList(String text) {
        if (text == null) return List.of();
        try {
            List<String> values = json.readValue(text, STRING_LIST);
            return values == null ? List.of() : values;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("A stored JSON list could not be read", e);
        }
    }

    private Object jsonValue(String text) {
        if (text == null) return null;
        try {
            return json.readValue(text, Object.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("A stored JSON value could not be read", e);
        }
    }
}
