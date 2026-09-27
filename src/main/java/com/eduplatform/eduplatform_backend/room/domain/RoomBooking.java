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
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "room_bookings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@SQLDelete(sql = "UPDATE room_bookings SET deleted_at = now() WHERE id = ? AND version = ?")
@SQLRestriction("deleted_at IS NULL")
public class RoomBooking extends SoftDeletable {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "room_id", nullable = false)
    private Room room;

    // ── Read-only views for listing ─────────────────────────────────────────
    // A booking outlives its room: a room can be deleted (soft) with bookings on record, and the
    // lazy `room` proxy then points at a row the Room entity's soft-delete restriction hides, so
    // initialising it threw EntityNotFoundException and every list holding such a booking failed
    // with 500 — the admin's Booking requests tabs and the tutor's own requests alike. What the
    // lists show is therefore read straight from the columns and plain subselects, which see the
    // row whether or not it is deleted. Never written; `room`, `tutor` and `offlineCourse` are.

    @Column(name = "room_id", insertable = false, updatable = false)
    private UUID roomId;

    @Formula("(select r.name from rooms r where r.id = room_id)")
    private String roomName;

    @Formula("(select r.status from rooms r where r.id = room_id)")
    private String roomStatus;

    @Formula("(select concat(u.first_name, ' ', u.last_name) from tutor_profiles t join users u on u.id = t.user_id where t.id = tutor_id)")
    private String tutorName;

    @Formula("(select u.email from tutor_profiles t join users u on u.id = t.user_id where t.id = tutor_id)")
    private String tutorEmail;

    @Formula("(select c.title from courses c where c.id = offline_course_id)")
    private String offlineCourseTitle;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "offline_course_id")
    private OfflineCourseDetails offlineCourse;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tutor_id", nullable = false)
    private TutorProfile tutor;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "ends_at", nullable = false)
    private Instant endsAt;

    /** Optional RFC 5545 RRULE for recurring bookings. */
    @Column(name = "recurrence_rule", length = 255)
    private String recurrenceRule;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private BookingStatus status = BookingStatus.PENDING;

    @Column(name = "total_fee", nullable = false, precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal totalFee = BigDecimal.ZERO;

    @Column(name = "currency", nullable = false, length = 3)
    @Builder.Default
    private String currency = "USD";
}
