package com.eduplatform.eduplatform_backend.room.domain;

import com.eduplatform.eduplatform_backend.common.domain.SoftDeletable;
import com.eduplatform.eduplatform_backend.common.enums.BookingStatus;
import com.eduplatform.eduplatform_backend.course.domain.OfflineCourseDetails;
import com.eduplatform.eduplatform_backend.tutor.domain.TutorProfile;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Formula;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * A long-term room request: the same hours on chosen weekdays over a period, asked for and
 * decided once. Each occurrence is an ordinary {@link RoomBooking} pointing back here through
 * {@code series_id}, so the overlap constraint on approved bookings covers every one of them.
 */
@Entity
@Table(name = "room_booking_series")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@SQLDelete(sql = "UPDATE room_booking_series SET deleted_at = now() WHERE id = ? AND version = ?")
@SQLRestriction("deleted_at IS NULL")
public class RoomBookingSeries extends SoftDeletable {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "room_id", nullable = false)
    private Room room;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "offline_course_id")
    private OfflineCourseDetails offlineCourse;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tutor_id", nullable = false)
    private TutorProfile tutor;

    // ── Read-only views, for the same reason as on RoomBooking: a room, course or profile can be
    // soft-deleted while the series is on record, and initialising its proxy would then throw.

    @Column(name = "room_id", insertable = false, updatable = false)
    private UUID roomId;

    @Formula("(select r.name from rooms r where r.id = room_id)")
    private String roomName;

    @Column(name = "offline_course_id", insertable = false, updatable = false)
    private UUID offlineCourseId;

    @Formula("(select c.title from courses c where c.id = offline_course_id)")
    private String offlineCourseTitle;

    @Column(name = "tutor_id", insertable = false, updatable = false)
    private UUID tutorId;

    @Formula("(select t.user_id from tutor_profiles t where t.id = tutor_id)")
    private UUID tutorUserId;

    @Formula("(select concat(u.first_name, ' ', u.last_name) from tutor_profiles t join users u on u.id = t.user_id where t.id = tutor_id)")
    private String tutorName;

    @Formula("(select u.email from tutor_profiles t join users u on u.id = t.user_id where t.id = tutor_id)")
    private String tutorEmail;

    @Formula("(select count(*) from room_bookings b where b.series_id = id and b.deleted_at is null)")
    private int occurrenceCount;

    /** Occurrences still to come that may yet take place: not started, and pending or approved. */
    @Formula("(select count(*) from room_bookings b where b.series_id = id and b.deleted_at is null"
            + " and b.starts_at > now() and b.status in ('PENDING', 'APPROVED'))")
    private int upcomingCount;

    // ── The request ───────────────────────────────────────────────────────

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    /** Bit mask, bit 0 = Monday … bit 6 = Sunday; see {@link #days()}. */
    @Column(name = "days_of_week", nullable = false)
    private short daysOfWeek;

    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalTime endTime;

    /** The zone the dates and times were expanded in. */
    @Column(name = "timezone", nullable = false, length = 64)
    private String timezone;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private BookingStatus status = BookingStatus.PENDING;

    /** The sum of the occurrences' fees as quoted at request time. */
    @Column(name = "total_fee", nullable = false, precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal totalFee = BigDecimal.ZERO;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "note", columnDefinition = "text")
    private String note;

    // ── The decision ──────────────────────────────────────────────────────

    @Column(name = "decision_note", columnDefinition = "text")
    private String decisionNote;

    @Column(name = "decided_by")
    private UUID decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    public Set<DayOfWeek> days() {
        Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
        for (DayOfWeek day : DayOfWeek.values()) {
            if ((daysOfWeek & bit(day)) != 0) days.add(day);
        }
        return days;
    }

    public static short mask(Collection<DayOfWeek> days) {
        int mask = 0;
        for (DayOfWeek day : days) mask |= bit(day);
        return (short) mask;
    }

    private static int bit(DayOfWeek day) {
        return 1 << (day.getValue() - 1);
    }
}
