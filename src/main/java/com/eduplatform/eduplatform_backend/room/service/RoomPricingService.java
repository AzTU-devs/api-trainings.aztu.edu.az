package com.eduplatform.eduplatform_backend.room.service;

import com.eduplatform.eduplatform_backend.audit.service.AuditService;
import com.eduplatform.eduplatform_backend.common.error.Errors;
import com.eduplatform.eduplatform_backend.room.domain.Room;
import com.eduplatform.eduplatform_backend.room.domain.RoomPricingRule;
import com.eduplatform.eduplatform_backend.room.repo.RoomPricingRuleRepository;
import com.eduplatform.eduplatform_backend.room.repo.RoomRepository;
import com.eduplatform.eduplatform_backend.room.web.dto.RoomPricingRuleDto;
import com.eduplatform.eduplatform_backend.room.web.dto.RoomPricingUpsertRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Time-bounded / tiered pricing rules layered on top of a room's flat {@code hourlyRate}, and the
 * resolution of the rate a booking is charged ({@link #resolve}). Days of the week are numbered
 * 0 = Sunday … 6 = Saturday, as the dashboard numbers them (the database agrees since V17).
 */
@Service
public class RoomPricingService {

    private final RoomRepository rooms;
    private final RoomPricingRuleRepository rules;
    private final AuditService audit;
    private final ZoneId zone;

    public RoomPricingService(RoomRepository rooms, RoomPricingRuleRepository rules, AuditService audit,
                              @Value("${app.timezone:Asia/Baku}") String zone) {
        this.rooms = rooms;
        this.rules = rules;
        this.audit = audit;
        this.zone = ZoneId.of(zone);
    }

    /** A price per hour and the currency it is in. */
    public record Rate(BigDecimal hourlyRate, String currency) {}

    /**
     * The rate that applies to a booking of {@code room} from {@code startsAt} to {@code endsAt}:
     * the highest-priority rule that matches, else the room's own hourly rate and currency. The
     * pricing page has always said "the highest-priority matching rule applies", and until now
     * nothing applied any rule — every booking was charged the flat rate.
     *
     * <p>A rule matches when each of its conditions that is set holds, judged in the university's
     * time zone (a rule for "Monday 09:00-12:00" means Baku time, not UTC):
     * <ul>
     *   <li>{@code dayOfWeek} is the day the booking starts, 0 = Sunday … 6 = Saturday;</li>
     *   <li>the booking lies within {@code startTime}–{@code endTime}, on a single day;</li>
     *   <li>the booking's date lies within {@code validFrom}–{@code validTo}, both inclusive.</li>
     * </ul>
     * Rules of equal priority are taken in the order the repository returns them, which the
     * pricing page shows too.
     */
    @Transactional(readOnly = true)
    public Rate resolve(Room room, Instant startsAt, Instant endsAt) {
        ZonedDateTime start = startsAt.atZone(zone);
        ZonedDateTime end = endsAt.atZone(zone);
        for (RoomPricingRule rule : rules.findAllByRoomIdOrderByPriorityDesc(room.getId())) {
            if (matches(rule, start, end)) {
                return new Rate(rule.getHourlyRate(), rule.getCurrency());
            }
        }
        return new Rate(room.getHourlyRate(), room.getCurrency());
    }

    static boolean matches(RoomPricingRule rule, ZonedDateTime start, ZonedDateTime end) {
        if (rule.getDayOfWeek() != null && rule.getDayOfWeek() != start.getDayOfWeek().getValue() % 7) {
            return false;
        }
        if (rule.getStartTime() != null || rule.getEndTime() != null) {
            // A window of hours only describes a booking that starts and ends on the same day.
            if (!end.toLocalDate().equals(start.toLocalDate())) return false;
            if (rule.getStartTime() != null && start.toLocalTime().isBefore(rule.getStartTime())) return false;
            if (rule.getEndTime() != null && end.toLocalTime().isAfter(rule.getEndTime())) return false;
        }
        LocalDate day = start.toLocalDate();
        if (rule.getValidFrom() != null && day.isBefore(rule.getValidFrom())) return false;
        return rule.getValidTo() == null || !day.isAfter(rule.getValidTo());
    }

    @Transactional(readOnly = true)
    public List<RoomPricingRuleDto> list(UUID roomId) {
        requireRoom(roomId);
        return rules.findAllByRoomIdOrderByPriorityDesc(roomId).stream().map(RoomPricingService::toDto).toList();
    }

    @Transactional
    public RoomPricingRuleDto create(UUID roomId, RoomPricingUpsertRequest req) {
        Room room = requireRoom(roomId);
        validateWindow(req);
        RoomPricingRule rule = RoomPricingRule.builder()
                .room(room)
                .name(req.name())
                .hourlyRate(req.hourlyRate())
                .currency(req.currency())
                .dayOfWeek(req.dayOfWeek())
                .startTime(req.startTime())
                .endTime(req.endTime())
                .validFrom(req.validFrom())
                .validTo(req.validTo())
                .priority(req.priority() == null ? 0 : req.priority())
                .build();
        rule.setId(UUID.randomUUID());
        RoomPricingRuleDto created = toDto(rules.save(rule));
        audit.record(AuditService.Actions.CREATE, "ROOM_PRICING_RULE", created.id(), null, auditableFields(created));
        return created;
    }

    @Transactional
    public RoomPricingRuleDto update(UUID roomId, UUID ruleId, RoomPricingUpsertRequest req) {
        validateWindow(req);
        RoomPricingRule rule = require(roomId, ruleId);
        Map<String, Object> before = auditableFields(toDto(rule));
        rule.setName(req.name());
        rule.setHourlyRate(req.hourlyRate());
        rule.setCurrency(req.currency());
        rule.setDayOfWeek(req.dayOfWeek());
        rule.setStartTime(req.startTime());
        rule.setEndTime(req.endTime());
        rule.setValidFrom(req.validFrom());
        rule.setValidTo(req.validTo());
        if (req.priority() != null) rule.setPriority(req.priority());
        RoomPricingRuleDto updated = toDto(rules.save(rule));
        audit.record(AuditService.Actions.UPDATE, "ROOM_PRICING_RULE", updated.id(), before, auditableFields(updated));
        return updated;
    }

    @Transactional
    public void delete(UUID roomId, UUID ruleId) {
        RoomPricingRule rule = require(roomId, ruleId);
        rules.delete(rule);
        audit.record(AuditService.Actions.DELETE, "ROOM_PRICING_RULE", ruleId, auditableFields(toDto(rule)), null);
    }

    private static Map<String, Object> auditableFields(RoomPricingRuleDto r) {
        return AuditService.snapshot(
                "roomId", r.roomId().toString(), "name", r.name(),
                "hourlyRate", r.hourlyRate(),   // compared by value; see AuditService.snapshot
                "currency", r.currency(), "dayOfWeek", r.dayOfWeek(),
                "startTime", r.startTime() == null ? null : r.startTime().toString(),
                "endTime", r.endTime() == null ? null : r.endTime().toString(),
                "validFrom", r.validFrom() == null ? null : r.validFrom().toString(),
                "validTo", r.validTo() == null ? null : r.validTo().toString(),
                "priority", r.priority());
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private Room requireRoom(UUID roomId) {
        return rooms.findById(roomId)
                .orElseThrow(() -> Errors.notFound("ROOM_NOT_FOUND", "Room does not exist"));
    }

    private RoomPricingRule require(UUID roomId, UUID ruleId) {
        RoomPricingRule rule = rules.findById(ruleId)
                .orElseThrow(() -> Errors.notFound("PRICING_RULE_NOT_FOUND", "Pricing rule does not exist"));
        if (!rule.getRoom().getId().equals(roomId)) {
            throw Errors.badRequest("PRICING_RULE_ROOM_MISMATCH", "Pricing rule does not belong to this room");
        }
        return rule;
    }

    private static void validateWindow(RoomPricingUpsertRequest req) {
        if (req.dayOfWeek() != null && (req.dayOfWeek() < 0 || req.dayOfWeek() > 6)) {
            throw Errors.badRequest("INVALID_DAY_OF_WEEK", "dayOfWeek must be 0 (Sun) – 6 (Sat)");
        }
        if (req.startTime() != null && req.endTime() != null && !req.endTime().isAfter(req.startTime())) {
            throw Errors.badRequest("INVALID_TIME_RANGE", "endTime must be after startTime");
        }
        if (req.validFrom() != null && req.validTo() != null && req.validTo().isBefore(req.validFrom())) {
            throw Errors.badRequest("INVALID_DATE_RANGE", "validTo must be on or after validFrom");
        }
    }

    private static RoomPricingRuleDto toDto(RoomPricingRule r) {
        return new RoomPricingRuleDto(
                r.getId(), r.getRoom().getId(), r.getName(), r.getHourlyRate(), r.getCurrency(),
                r.getDayOfWeek(), r.getStartTime(), r.getEndTime(),
                r.getValidFrom(), r.getValidTo(), r.getPriority());
    }
}
