package com.eduplatform.eduplatform_backend.room.service;

import com.eduplatform.eduplatform_backend.audit.service.AuditService;
import com.eduplatform.eduplatform_backend.common.enums.BookingDecision;
import com.eduplatform.eduplatform_backend.common.enums.BookingStatus;
import com.eduplatform.eduplatform_backend.common.error.Errors;
import com.eduplatform.eduplatform_backend.identity.domain.User;
import com.eduplatform.eduplatform_backend.identity.repo.UserRepository;
import com.eduplatform.eduplatform_backend.room.domain.Room;
import com.eduplatform.eduplatform_backend.room.domain.RoomBooking;
import com.eduplatform.eduplatform_backend.room.domain.RoomBookingApproval;
import com.eduplatform.eduplatform_backend.room.repo.RoomBookingApprovalRepository;
import com.eduplatform.eduplatform_backend.room.repo.RoomBookingRepository;
import com.eduplatform.eduplatform_backend.room.web.dto.BookingCreateRequest;
import com.eduplatform.eduplatform_backend.tutor.domain.TutorProfile;
import com.eduplatform.eduplatform_backend.tutor.repo.TutorProfileRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Service
public class RoomBookingService {

    private final RoomBookingRepository bookings;
    private final RoomBookingApprovalRepository approvals;
    private final RoomService rooms;
    private final TutorProfileRepository tutors;
    private final UserRepository users;
    private final AuditService audit;

    public RoomBookingService(RoomBookingRepository bookings, RoomBookingApprovalRepository approvals,
                              RoomService rooms, TutorProfileRepository tutors, UserRepository users,
                              AuditService audit) {
        this.bookings = bookings;
        this.approvals = approvals;
        this.rooms = rooms;
        this.tutors = tutors;
        this.users = users;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public Page<RoomBooking> listByStatus(BookingStatus status, Pageable pageable) {
        Page<RoomBooking> page = bookings.findAllByStatus(status, pageable);
        page.forEach(RoomBookingService::initForMapping);
        return page;
    }

    @Transactional(readOnly = true)
    public Page<RoomBooking> listMine(UUID userId, Pageable pageable) {
        Page<RoomBooking> page = bookings.findAllByTutorUser(userId, pageable);
        page.forEach(RoomBookingService::initForMapping);
        return page;
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

    /** Initialize the lazy {@code room} the booking mapper reads, before the session closes. */
    private static void initForMapping(RoomBooking b) {
        b.getRoom().getName();
    }

    @Transactional
    public RoomBooking requestBooking(UUID userId, BookingCreateRequest req) {
        TutorProfile tutor = tutors.findByUserId(userId)
                .orElseThrow(() -> Errors.forbidden("NOT_A_TUTOR", "Only tutors can request room bookings"));
        Room room = rooms.get(req.roomId());

        if (!req.endsAt().isAfter(req.startsAt())) {
            throw Errors.badRequest("INVALID_RANGE", "endsAt must be after startsAt");
        }
        if (!bookings.findOverlappingApproved(room.getId(), req.startsAt(), req.endsAt()).isEmpty()) {
            throw Errors.conflict("ROOM_TIME_TAKEN", "Requested time overlaps an existing approved booking");
        }

        double hours = Duration.between(req.startsAt(), req.endsAt()).toMinutes() / 60.0;
        java.math.BigDecimal fee = room.getHourlyRate().multiply(java.math.BigDecimal.valueOf(hours))
                .setScale(2, java.math.RoundingMode.HALF_UP);

        RoomBooking booking = RoomBooking.builder()
                .room(room)
                .tutor(tutor)
                .startsAt(req.startsAt())
                .endsAt(req.endsAt())
                .recurrenceRule(req.recurrenceRule())
                .status(BookingStatus.PENDING)
                .totalFee(fee)
                .currency(room.getCurrency())
                .build();
        booking.setId(UUID.randomUUID());
        return bookings.save(booking);
    }

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

        booking.setStatus(decision == BookingDecision.APPROVED ? BookingStatus.APPROVED : BookingStatus.REJECTED);
        bookings.save(booking);

        RoomBookingApproval row = RoomBookingApproval.builder()
                .booking(booking)
                .decision(decision)
                .decisionNote(note)
                .decidedBy(admin)
                .decidedAt(Instant.now())
                .build();
        row.setId(UUID.randomUUID());
        approvals.save(row);
        audit.record(decision == BookingDecision.APPROVED ? AuditService.Actions.APPROVE : AuditService.Actions.REJECT,
                "ROOM_BOOKING", booking.getId(), null,
                AuditService.snapshot("status", booking.getStatus().name(), "note", note));
        // findById left room as a proxy. Without this the controller's mapping fails after
        // the decision has already committed, and the admin's retry gets a 409.
        initForMapping(booking);
        return booking;
    }
}
