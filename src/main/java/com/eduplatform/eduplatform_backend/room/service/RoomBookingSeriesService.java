package com.eduplatform.eduplatform_backend.room.service;

import com.eduplatform.eduplatform_backend.audit.service.AuditService;
import com.eduplatform.eduplatform_backend.common.enums.BookingDecision;
import com.eduplatform.eduplatform_backend.common.enums.BookingStatus;
import com.eduplatform.eduplatform_backend.common.error.AppException;
import com.eduplatform.eduplatform_backend.common.error.Errors;
import com.eduplatform.eduplatform_backend.common.security.AuthenticatedPrincipal;
import com.eduplatform.eduplatform_backend.course.domain.OfflineCourseDetails;
import com.eduplatform.eduplatform_backend.notification.service.DecisionEvents;
import com.eduplatform.eduplatform_backend.room.domain.Room;
import com.eduplatform.eduplatform_backend.room.domain.RoomBooking;
import com.eduplatform.eduplatform_backend.room.domain.RoomBookingSeries;
import com.eduplatform.eduplatform_backend.room.repo.RoomBookingRepository;
import com.eduplatform.eduplatform_backend.room.repo.RoomBookingSeriesRepository;
import com.eduplatform.eduplatform_backend.room.repo.RoomRepository;
import com.eduplatform.eduplatform_backend.room.web.dto.BookingSeriesCreateRequest;
import com.eduplatform.eduplatform_backend.room.web.dto.RoomBookingSeriesDto;
import com.eduplatform.eduplatform_backend.tutor.domain.TutorProfile;
import com.eduplatform.eduplatform_backend.tutor.repo.TutorProfileRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Long-term room requests: one request for the same hours on chosen weekdays over a period,
 * which an admin decides once. Every occurrence is stored as an ordinary room booking linked to
 * the series, so the database's overlap constraint on approved bookings guards each of them, and
 * the room's own booking lists and calendars show them as the bookings they are.
 *
 * <p>Everything a single booking must satisfy, an occurrence satisfies too: the room exists and
 * is AVAILABLE, the training (if any) is one the expert teaches, no approved booking covers the
 * time, and the fee follows the room's pricing rules. The checks are RoomBookingService's own.
 */
@Service
public class RoomBookingSeriesService {

    /** Above this, a request is better split; it also bounds the rows one request writes. */
    static final int MAX_OCCURRENCES = 200;
    /** The longest period, startDate and endDate included: a year, leap day and all. */
    static final int MAX_DAYS = 366;
    /** Conflicting dates named in a message before the rest are only counted. */
    private static final int LISTED_DATES = 10;

    private static final Set<BookingStatus> LIVE = EnumSet.of(BookingStatus.PENDING, BookingStatus.APPROVED);

    private final RoomBookingSeriesRepository series;
    private final RoomBookingRepository bookings;
    private final RoomBookingService singleBookings;
    private final RoomService roomService;
    private final RoomRepository rooms;
    private final RoomPricingService pricing;
    private final TutorProfileRepository tutors;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final ZoneId zone;

    @PersistenceContext
    private EntityManager entityManager;

    public RoomBookingSeriesService(RoomBookingSeriesRepository series, RoomBookingRepository bookings,
                                    RoomBookingService singleBookings, RoomService roomService,
                                    RoomRepository rooms, RoomPricingService pricing,
                                    TutorProfileRepository tutors, AuditService audit,
                                    ApplicationEventPublisher events,
                                    @Value("${app.timezone:Asia/Baku}") String zone) {
        this.series = series;
        this.bookings = bookings;
        this.singleBookings = singleBookings;
        this.roomService = roomService;
        this.rooms = rooms;
        this.pricing = pricing;
        this.tutors = tutors;
        this.audit = audit;
        this.events = events;
        this.zone = ZoneId.of(zone);
    }

    /** One occurrence of the pattern: its local date and its time as instants. */
    record Slot(LocalDate date, Instant startsAt, Instant endsAt) {}

    // ── Requesting ─────────────────────────────────────────────────────────

    /**
     * Requests the series: the series row and one PENDING booking per occurrence, all in one
     * transaction. Refused, and nothing written, when any occurrence overlaps an approved booking
     * of the room (409 ROOM_BOOKING_CONFLICT naming the dates), so the expert can pick other days
     * instead of finding out occurrence by occurrence.
     */
    @Transactional
    public RoomBookingSeriesDto create(UUID userId, BookingSeriesCreateRequest req) {
        TutorProfile tutor = tutors.findByUserId(userId)
                .orElseThrow(() -> Errors.forbidden("NOT_A_TUTOR", "Only tutors can request room bookings"));

        // Seconds mean nothing on a timetable and would only show up as odd end times.
        LocalTime startTime = req.startTime().truncatedTo(ChronoUnit.MINUTES);
        LocalTime endTime = req.endTime().truncatedTo(ChronoUnit.MINUTES);
        if (req.endDate().isBefore(req.startDate())) {
            throw Errors.badRequest("INVALID_DATE_RANGE", "endDate must be on or after startDate");
        }
        if (ChronoUnit.DAYS.between(req.startDate(), req.endDate()) + 1 > MAX_DAYS) {
            throw Errors.badRequest("INVALID_DATE_RANGE",
                    "A booking series can cover at most " + MAX_DAYS + " days");
        }
        if (!endTime.isAfter(startTime)) {
            throw Errors.badRequest("INVALID_TIME_RANGE", "endTime must be after startTime");
        }
        Set<DayOfWeek> days = req.daysOfWeek() == null ? EnumSet.noneOf(DayOfWeek.class)
                : req.daysOfWeek().stream().filter(Objects::nonNull)
                        .collect(Collectors.toCollection(() -> EnumSet.noneOf(DayOfWeek.class)));
        if (days.isEmpty()) {
            throw Errors.badRequest("DAYS_REQUIRED", "Choose at least one day of the week");
        }

        List<Slot> slots = expand(req.startDate(), req.endDate(), days, startTime, endTime);
        if (slots.isEmpty()) {
            throw Errors.badRequest("NO_OCCURRENCES", "None of the chosen weekdays falls within the period");
        }
        if (slots.size() > MAX_OCCURRENCES) {
            throw Errors.badRequest("TOO_MANY_OCCURRENCES",
                    "A booking series can hold at most " + MAX_OCCURRENCES + " sessions; this one would hold "
                            + slots.size() + ". Shorten the period or choose fewer days");
        }
        if (!slots.get(0).startsAt().isAfter(Instant.now())) {
            throw Errors.badRequest("PAST_START", "The first session must be in the future");
        }

        Room room = roomService.get(req.roomId());
        RoomBookingService.requireAvailable(room);
        OfflineCourseDetails offlineCourse = req.offlineCourseId() == null
                ? null : singleBookings.requireTaughtOfflineCourse(req.offlineCourseId(), userId);
        List<LocalDate> taken = conflicts(room.getId(), slots, null);
        if (!taken.isEmpty()) {
            throw conflict(taken);
        }

        List<RoomPricingService.Rate> rates = pricing.resolveAll(room, slots.stream()
                .map(s -> new RoomPricingService.Period(s.startsAt(), s.endsAt())).toList());
        Set<String> currencies = rates.stream().map(RoomPricingService.Rate::currency).collect(Collectors.toSet());
        if (currencies.size() > 1) {
            // A total across currencies would mean nothing; the room's pricing has to be put right.
            throw Errors.conflict("PRICING_CURRENCY_MISMATCH",
                    "This room's prices for these sessions are in different currencies ("
                            + String.join(", ", new TreeSet<>(currencies))
                            + "); ask an administrator to align the room's pricing rules");
        }

        UUID seriesId = UUID.randomUUID();
        List<RoomBooking> occurrences = new ArrayList<>(slots.size());
        BigDecimal total = BigDecimal.ZERO;
        for (int i = 0; i < slots.size(); i++) {
            Slot slot = slots.get(i);
            RoomPricingService.Rate rate = rates.get(i);
            BigDecimal fee = RoomPricingService.fee(rate.hourlyRate(), slot.startsAt(), slot.endsAt());
            total = total.add(fee);
            RoomBooking booking = RoomBooking.builder()
                    .room(room)
                    .tutor(tutor)
                    .offlineCourse(offlineCourse)
                    .startsAt(slot.startsAt())
                    .endsAt(slot.endsAt())
                    .status(BookingStatus.PENDING)
                    .totalFee(fee)
                    .currency(rate.currency())
                    .seriesId(seriesId)
                    .build();
            booking.setId(UUID.randomUUID());
            occurrences.add(booking);
        }

        RoomBookingSeries row = RoomBookingSeries.builder()
                .room(room)
                .tutor(tutor)
                .offlineCourse(offlineCourse)
                .startDate(req.startDate())
                .endDate(req.endDate())
                .daysOfWeek(RoomBookingSeries.mask(days))
                .startTime(startTime)
                .endTime(endTime)
                .timezone(zone.getId())
                .status(BookingStatus.PENDING)
                .totalFee(total)
                .currency(rates.get(0).currency())
                .note(blankToNull(req.note()))
                .build();
        row.setId(seriesId);
        // persist rather than save: every row is new, and save() would first look each of up to
        // 200 ids up (merge) because the id is set here. The series goes first, as every
        // occurrence references it; the inserts are batched.
        entityManager.persist(row);
        occurrences.forEach(entityManager::persist);
        entityManager.flush();
        // Re-read, so the response carries the names and counts the database computes.
        entityManager.refresh(row);
        audit.record(AuditService.Actions.CREATE, "ROOM_BOOKING_SERIES", row.getId(), null,
                AuditService.snapshot("roomId", room.getId().toString(), "occurrences", occurrences.size(),
                        "totalFee", total));
        return toDto(row, occurrences);
    }

    /** Every date in the period whose weekday is chosen, at the given local times. */
    private List<Slot> expand(LocalDate from, LocalDate to, Set<DayOfWeek> days,
                              LocalTime startTime, LocalTime endTime) {
        List<Slot> slots = new ArrayList<>();
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            if (days.contains(day.getDayOfWeek())) {
                slots.add(new Slot(day,
                        ZonedDateTime.of(day, startTime, zone).toInstant(),
                        ZonedDateTime.of(day, endTime, zone).toInstant()));
            }
        }
        return slots;
    }

    /**
     * The local dates of the slots that overlap an approved booking of the room, other than the
     * series' own occurrences. One query for the whole period, then compared in memory.
     */
    private List<LocalDate> conflicts(UUID roomId, List<Slot> slots, UUID ownSeriesId) {
        if (slots.isEmpty()) return List.of();
        List<RoomBooking> approved = bookings.findOverlappingApproved(roomId,
                slots.get(0).startsAt(), slots.get(slots.size() - 1).endsAt());
        List<LocalDate> taken = new ArrayList<>();
        for (Slot slot : slots) {
            boolean clash = approved.stream()
                    .filter(b -> ownSeriesId == null || !ownSeriesId.equals(b.getSeriesId()))
                    .anyMatch(b -> b.getStartsAt().isBefore(slot.endsAt()) && b.getEndsAt().isAfter(slot.startsAt()));
            if (clash) taken.add(slot.date());
        }
        return taken;
    }

    static AppException conflict(List<LocalDate> dates) {
        String listed = dates.stream().limit(LISTED_DATES).map(LocalDate::toString)
                .collect(Collectors.joining(", "));
        int more = dates.size() - LISTED_DATES;
        return Errors.conflict("ROOM_BOOKING_CONFLICT",
                "The room is already booked at this time on " + listed + (more > 0 ? " and " + more + " more" : ""));
    }

    // ── Reading ────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Page<RoomBookingSeriesDto> listMine(UUID userId, Pageable pageable) {
        return series.findAllByTutorUser(userId, unsorted(pageable)).map(s -> toDto(s, List.of()));
    }

    @Transactional(readOnly = true)
    public Page<RoomBookingSeriesDto> listByStatus(BookingStatus status, Pageable pageable) {
        return series.findAllByStatusOldestFirst(status, unsorted(pageable)).map(s -> toDto(s, List.of()));
    }

    /** The requesting expert, or anyone who decides bookings, may read a series in full. */
    @Transactional(readOnly = true)
    public RoomBookingSeriesDto get(AuthenticatedPrincipal me, UUID seriesId) {
        RoomBookingSeries s = require(seriesId);
        boolean moderator = me.permissions() != null && me.permissions().contains("room:approve");
        if (!moderator && !me.userId().equals(s.getTutorUserId())) {
            throw notOwner();
        }
        return toDto(s, bookings.findAllBySeriesIdOrderByStartsAtAsc(seriesId));
    }

    // ── Cancelling ─────────────────────────────────────────────────────────

    /**
     * The expert withdraws the series: it becomes CANCELLED, and so does every occurrence that has
     * not started yet. Those that have started or are over keep their status, since they are
     * history (an approved past session did take place). Cancelling twice changes nothing.
     */
    @Transactional
    public void cancel(UUID userId, UUID seriesId) {
        RoomBookingSeries s = require(seriesId);
        if (!userId.equals(s.getTutorUserId())) {
            throw notOwner();
        }
        if (s.getStatus() == BookingStatus.CANCELLED) {
            return;
        }
        if (s.getStatus() == BookingStatus.REJECTED) {
            throw Errors.conflict("INVALID_BOOKING_TRANSITION", "A rejected booking series cannot be cancelled");
        }
        Instant now = dbNow();
        List<RoomBooking> upcoming = bookings.findAllBySeriesIdOrderByStartsAtAsc(seriesId).stream()
                .filter(b -> b.getStartsAt().isAfter(now) && LIVE.contains(b.getStatus()))
                .toList();
        upcoming.forEach(b -> b.setStatus(BookingStatus.CANCELLED));
        bookings.saveAll(upcoming);
        s.setStatus(BookingStatus.CANCELLED);
        series.save(s);
        audit.record(AuditService.Actions.UPDATE, "ROOM_BOOKING_SERIES", s.getId(), null,
                AuditService.snapshot("status", BookingStatus.CANCELLED.name(), "occurrencesCancelled", upcoming.size()));
    }

    // ── Deciding ───────────────────────────────────────────────────────────

    /**
     * Approves or rejects a pending series as a whole, in one transaction.
     *
     * <p>Approving approves every pending occurrence that has not started; one that already has is
     * left as it is, as a single booking whose time has passed would be. Like a single approval it
     * re-checks the room (still there, still AVAILABLE) and the time: if any occurrence overlaps a
     * booking approved meanwhile, nothing is approved and the answer is 409 ROOM_BOOKING_CONFLICT
     * naming the dates. The exclusion constraint stays the last word for approvals racing each
     * other, and its violation is answered with the same code, the whole transaction rolled back.
     *
     * <p>Rejecting needs a note, which tells the expert why, and rejects every pending occurrence.
     * The expert is told either way once the decision has committed.
     */
    @Transactional
    public RoomBookingSeriesDto decide(UUID seriesId, UUID adminUserId, BookingDecision decision, String note) {
        RoomBookingSeries s = require(seriesId);
        boolean approve = decision == BookingDecision.APPROVED;
        String decisionNote = blankToNull(note);
        if (!approve && decisionNote == null) {
            throw Errors.badRequest("NOTE_REQUIRED", "Say why the booking series is rejected");
        }
        if (s.getStatus() != BookingStatus.PENDING) {
            throw Errors.conflict("INVALID_BOOKING_TRANSITION",
                    "Booking series is not pending and cannot receive a decision");
        }

        Instant now = dbNow();
        List<RoomBooking> occurrences = bookings.findAllBySeriesIdOrderByStartsAtAsc(seriesId);
        List<RoomBooking> targets;
        if (approve) {
            Room room = rooms.findById(s.getRoomId()).orElse(null);
            if (room == null) {
                throw Errors.conflict("ROOM_NOT_AVAILABLE", "The room has been deleted");
            }
            RoomBookingService.requireAvailable(room);
            targets = occurrences.stream()
                    .filter(b -> b.getStatus() == BookingStatus.PENDING && b.getStartsAt().isAfter(now))
                    .toList();
            List<Slot> slots = targets.stream()
                    .map(b -> new Slot(b.getStartsAt().atZone(zone).toLocalDate(), b.getStartsAt(), b.getEndsAt()))
                    .toList();
            List<LocalDate> taken = conflicts(room.getId(), slots, s.getId());
            if (!taken.isEmpty()) {
                throw conflict(taken);
            }
        } else {
            targets = occurrences.stream().filter(b -> b.getStatus() == BookingStatus.PENDING).toList();
        }

        targets.forEach(b -> b.setStatus(approve ? BookingStatus.APPROVED : BookingStatus.REJECTED));
        s.setStatus(approve ? BookingStatus.APPROVED : BookingStatus.REJECTED);
        s.setDecisionNote(decisionNote);
        s.setDecidedBy(adminUserId);
        s.setDecidedAt(now);
        try {
            bookings.saveAllAndFlush(targets);
            series.saveAndFlush(s);
        } catch (DataIntegrityViolationException raced) {
            // Another approval for the same room committed between the check and this write.
            throw Errors.conflict("ROOM_BOOKING_CONFLICT",
                    "Another booking of this room was approved for one of these times meanwhile; reload and try again");
        }
        entityManager.refresh(s);

        audit.record(approve ? AuditService.Actions.APPROVE : AuditService.Actions.REJECT,
                "ROOM_BOOKING_SERIES", s.getId(), null,
                AuditService.snapshot("status", s.getStatus().name(), "note", decisionNote,
                        "occurrences", targets.size()));
        if (s.getTutorUserId() != null) {
            events.publishEvent(new DecisionEvents.RoomBookingDecided(s.getTutorUserId(), null, s.getId(),
                    s.getRoomName(), schedule(s), approve, decisionNote));
        }
        return toDto(s, occurrences);
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private RoomBookingSeries require(UUID seriesId) {
        return series.findById(seriesId)
                .orElseThrow(() -> Errors.notFound("BOOKING_SERIES_NOT_FOUND", "Booking series does not exist"));
    }

    private static AppException notOwner() {
        return Errors.forbidden("NOT_BOOKING_OWNER", "Only the requesting tutor can manage this booking series");
    }

    /** The lists keep their own order; a client's sort would only reshuffle the queue. */
    private static Pageable unsorted(Pageable pageable) {
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());
    }

    /**
     * The current time at the precision the database keeps, so decidedAt in a response equals
     * the one read back later (a Linux clock reads nanoseconds, Postgres keeps microseconds).
     */
    private static Instant dbNow() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.trim();
    }

    /** "2026-11-02 – 2026-12-31, MON/WED 10:00–12:00": digits and ISO day abbreviations only. */
    private static String schedule(RoomBookingSeries s) {
        String days = s.days().stream().map(d -> d.name().substring(0, 3)).collect(Collectors.joining("/"));
        return s.getStartDate() + " – " + s.getEndDate() + ", " + days + " "
                + s.getStartTime() + "–" + s.getEndTime();
    }

    private static RoomBookingSeriesDto toDto(RoomBookingSeries s, List<RoomBooking> occurrences) {
        List<RoomBookingSeriesDto.Occurrence> rows = occurrences.stream()
                .map(b -> new RoomBookingSeriesDto.Occurrence(b.getId(), b.getStartsAt(), b.getEndsAt(), b.getStatus()))
                .toList();
        return new RoomBookingSeriesDto(
                s.getId(), s.getRoomId(), s.getRoomName(),
                s.getOfflineCourseId(), s.getOfflineCourseTitle(),
                s.getTutorId(), s.getTutorName(), s.getTutorEmail(),
                s.getStartDate(), s.getEndDate(),
                List.copyOf(s.days()), s.getStartTime(), s.getEndTime(), s.getTimezone(),
                s.getStatus(), s.getOccurrenceCount(), s.getUpcomingCount(),
                s.getTotalFee(), s.getCurrency(),
                s.getNote(), s.getDecisionNote(), s.getDecidedAt(), s.getCreatedAt(),
                rows);
    }
}
