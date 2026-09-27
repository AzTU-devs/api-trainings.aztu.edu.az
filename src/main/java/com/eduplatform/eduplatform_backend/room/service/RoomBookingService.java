package com.eduplatform.eduplatform_backend.room.service;

import com.eduplatform.eduplatform_backend.audit.service.AuditService;
import com.eduplatform.eduplatform_backend.common.enums.BookingDecision;
import com.eduplatform.eduplatform_backend.common.enums.BookingStatus;
import com.eduplatform.eduplatform_backend.common.enums.RoomStatus;
import com.eduplatform.eduplatform_backend.common.error.AppException;
import com.eduplatform.eduplatform_backend.common.error.Errors;
import com.eduplatform.eduplatform_backend.course.domain.Course;
import com.eduplatform.eduplatform_backend.course.domain.OfflineCourseDetails;
import com.eduplatform.eduplatform_backend.course.repo.OfflineCourseDetailsRepository;
import com.eduplatform.eduplatform_backend.course.service.CourseAccess;
import com.eduplatform.eduplatform_backend.identity.domain.User;
import com.eduplatform.eduplatform_backend.identity.repo.UserRepository;
import com.eduplatform.eduplatform_backend.room.domain.Room;
import com.eduplatform.eduplatform_backend.room.domain.RoomBooking;
import com.eduplatform.eduplatform_backend.room.domain.RoomBookingApproval;
import com.eduplatform.eduplatform_backend.room.repo.RoomBookingApprovalRepository;
import com.eduplatform.eduplatform_backend.room.repo.RoomBookingRepository;
import com.eduplatform.eduplatform_backend.room.repo.RoomRepository;
import com.eduplatform.eduplatform_backend.room.web.dto.BookingCreateRequest;
import com.eduplatform.eduplatform_backend.tutor.domain.TutorProfile;
import com.eduplatform.eduplatform_backend.tutor.repo.TutorProfileRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Tutors ask for a room; admins approve or reject.
 *
 * <p>The lists read what they show from the booking's own columns (see RoomBooking), so a room
 * that was deleted after it was booked no longer takes every list holding its bookings down.
 */
@Service
public class RoomBookingService {

    private final RoomBookingRepository bookings;
    private final RoomBookingApprovalRepository approvals;
    private final RoomService roomService;
    private final RoomRepository rooms;
    private final RoomPricingService pricing;
    private final TutorProfileRepository tutors;
    private final OfflineCourseDetailsRepository offlineCourses;
    private final UserRepository users;
    private final AuditService audit;

    @PersistenceContext
    private EntityManager entityManager;

    public RoomBookingService(RoomBookingRepository bookings, RoomBookingApprovalRepository approvals,
                              RoomService roomService, RoomRepository rooms, RoomPricingService pricing,
                              TutorProfileRepository tutors, OfflineCourseDetailsRepository offlineCourses,
                              UserRepository users, AuditService audit) {
        this.bookings = bookings;
        this.approvals = approvals;
        this.roomService = roomService;
        this.rooms = rooms;
        this.pricing = pricing;
        this.tutors = tutors;
        this.offlineCourses = offlineCourses;
        this.users = users;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public Page<RoomBooking> listByStatus(BookingStatus status, Pageable pageable) {
        return bookings.findAllByStatus(status, pageable);
    }

    @Transactional(readOnly = true)
    public Page<RoomBooking> listMine(UUID userId, Pageable pageable) {
        return bookings.findAllByTutorUser(userId, pageable);
    }

    @Transactional
    public void cancel(UUID userId, UUID bookingId) {
        RoomBooking booking = bookings.findById(bookingId)
                .orElseThrow(() -> Errors.notFound("BOOKING_NOT_FOUND", "Booking does not exist"));
        if (!booking.getTutor().getUser().getId().equals(userId)) {
            throw Errors.forbidden("NOT_BOOKING_OWNER", "Only the requesting tutor can cancel this booking");
        }
        if (booking.getStatus() == BookingStatus.CANCELLED) {
            return;
        }
        if (booking.getStatus() == BookingStatus.REJECTED) {
            throw Errors.conflict("INVALID_BOOKING_TRANSITION", "A rejected booking cannot be cancelled");
        }
        booking.setStatus(BookingStatus.CANCELLED);
        bookings.save(booking);
    }

    /**
     * A tutor's request for a room. Refused up front when the room cannot be had: it is not
     * AVAILABLE (under maintenance, reserved or retired — the status used to change nothing), or
     * an approved booking already covers the time.
     *
     * <p>The fee comes from the room's pricing rules (see RoomPricingService.resolve), in the
     * rule's currency; the flat hourly rate applies only where no rule matches. A recurring
     * request is refused: the RRULE used to be stored as text and neither expanded nor priced, so
     * the fee and the conflict checks covered the first session only.
     *
     * <p>An offline course named with the request must be one the tutor teaches; it used to be
     * accepted and silently dropped.
     */
    @Transactional
    public RoomBooking requestBooking(UUID userId, BookingCreateRequest req) {
        TutorProfile tutor = tutors.findByUserId(userId)
                .orElseThrow(() -> Errors.forbidden("NOT_A_TUTOR", "Only tutors can request room bookings"));
        Room room = roomService.get(req.roomId());

        if (!req.endsAt().isAfter(req.startsAt())) {
            throw Errors.badRequest("INVALID_RANGE", "endsAt must be after startsAt");
        }
        if (req.recurrenceRule() != null && !req.recurrenceRule().isBlank()) {
            throw Errors.badRequest("RECURRENCE_NOT_SUPPORTED",
                    "Recurring bookings are not supported yet; request each session separately");
        }
        requireAvailable(room);
        if (!bookings.findOverlappingApproved(room.getId(), req.startsAt(), req.endsAt()).isEmpty()) {
            throw timeTaken();
        }
        OfflineCourseDetails offlineCourse = req.offlineCourseId() == null
                ? null : requireTaughtOfflineCourse(req.offlineCourseId(), userId);

        RoomPricingService.Rate rate = pricing.resolve(room, req.startsAt(), req.endsAt());
        BigDecimal hours = BigDecimal.valueOf(Duration.between(req.startsAt(), req.endsAt()).toMinutes())
                .divide(BigDecimal.valueOf(60), 6, RoundingMode.HALF_UP);
        BigDecimal fee = rate.hourlyRate().multiply(hours).setScale(2, RoundingMode.HALF_UP);

        RoomBooking booking = RoomBooking.builder()
                .room(room)
                .tutor(tutor)
                .offlineCourse(offlineCourse)
                .startsAt(req.startsAt())
                .endsAt(req.endsAt())
                .status(BookingStatus.PENDING)
                .totalFee(fee)
                .currency(rate.currency())
                .build();
        booking.setId(UUID.randomUUID());
        RoomBooking saved = bookings.saveAndFlush(booking);
        // Re-read, so the response carries the room name and the requester, which are subselects
        // the database computes, not fields anything here set.
        entityManager.refresh(saved);
        return saved;
    }

    /**
     * Approves or rejects a pending booking. An approval re-checks what may have changed since the
     * request: the room must still be AVAILABLE and no other booking for the same time may have
     * been approved meanwhile. Both used to be left to the database's exclusion constraint, which
     * answered a double booking with a generic "conflicts with existing data". The constraint
     * stays the last word for two approvals racing each other, and is mapped to the same 409
     * ROOM_TIME_TAKEN. Rejecting needs neither check, so a request for a room that has since been
     * retired or deleted can still be turned down.
     */
    @Transactional
    public RoomBooking decide(UUID bookingId, UUID adminUserId, BookingDecision decision, String note) {
        RoomBooking booking = bookings.findById(bookingId)
                .orElseThrow(() -> Errors.notFound("BOOKING_NOT_FOUND", "Booking does not exist"));
        if (booking.getStatus() != BookingStatus.PENDING) {
            throw Errors.conflict("INVALID_BOOKING_TRANSITION",
                    "Booking is not pending and cannot receive a decision");
        }
        User admin = users.findById(adminUserId)
                .orElseThrow(() -> new IllegalStateException("Admin user vanished mid-request"));

        boolean approve = decision == BookingDecision.APPROVED;
        if (approve) {
            Room room = rooms.findById(booking.getRoomId()).orElse(null);
            if (room == null) {
                throw Errors.conflict("ROOM_NOT_AVAILABLE", "The room has been deleted");
            }
            requireAvailable(room);
            boolean clash = bookings.findOverlappingApproved(room.getId(), booking.getStartsAt(), booking.getEndsAt())
                    .stream().anyMatch(other -> !other.getId().equals(booking.getId()));
            if (clash) {
                throw timeTaken();
            }
        }

        booking.setStatus(approve ? BookingStatus.APPROVED : BookingStatus.REJECTED);
        try {
            bookings.saveAndFlush(booking);
        } catch (DataIntegrityViolationException raced) {
            throw timeTaken();
        }

        RoomBookingApproval row = RoomBookingApproval.builder()
                .booking(booking)
                .decision(decision)
                .decisionNote(note)
                .decidedBy(admin)
                .decidedAt(Instant.now())
                .build();
        row.setId(UUID.randomUUID());
        approvals.save(row);
        audit.record(approve ? AuditService.Actions.APPROVE : AuditService.Actions.REJECT,
                "ROOM_BOOKING", booking.getId(), null,
                AuditService.snapshot("status", booking.getStatus().name(), "note", note));
        return booking;
    }

    private static void requireAvailable(Room room) {
        if (room.getStatus() != RoomStatus.AVAILABLE) {
            throw Errors.conflict("ROOM_NOT_AVAILABLE",
                    "This room is " + room.getStatus() + " and cannot be booked");
        }
    }

    private static AppException timeTaken() {
        return Errors.conflict("ROOM_TIME_TAKEN", "Requested time overlaps an existing approved booking");
    }

    private OfflineCourseDetails requireTaughtOfflineCourse(UUID courseId, UUID userId) {
        OfflineCourseDetails details = offlineCourses.findById(courseId)
                .orElseThrow(() -> Errors.notFound("OFFLINE_COURSE_NOT_FOUND",
                        "No in-person training exists with id " + courseId));
        Course course = details.getCourse();
        if (course == null || course.isDeleted() || !CourseAccess.teaches(course, userId)) {
            throw Errors.forbidden("NOT_COURSE_TUTOR", "You can only book a room for a training you teach");
        }
        return details;
    }

}
