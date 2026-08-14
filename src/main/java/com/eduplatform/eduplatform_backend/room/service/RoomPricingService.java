package com.eduplatform.eduplatform_backend.room.service;

import com.eduplatform.eduplatform_backend.common.error.Errors;
import com.eduplatform.eduplatform_backend.room.domain.Room;
import com.eduplatform.eduplatform_backend.room.domain.RoomPricingRule;
import com.eduplatform.eduplatform_backend.room.repo.RoomPricingRuleRepository;
import com.eduplatform.eduplatform_backend.room.repo.RoomRepository;
import com.eduplatform.eduplatform_backend.room.web.dto.RoomPricingRuleDto;
import com.eduplatform.eduplatform_backend.room.web.dto.RoomPricingUpsertRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** Time-bounded / tiered pricing rules layered on top of a room's flat {@code hourlyRate}. */
@Service
public class RoomPricingService {

    private final RoomRepository rooms;
    private final RoomPricingRuleRepository rules;

    public RoomPricingService(RoomRepository rooms, RoomPricingRuleRepository rules) {
        this.rooms = rooms;
        this.rules = rules;
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
        return toDto(rules.save(rule));
    }

    @Transactional
    public RoomPricingRuleDto update(UUID roomId, UUID ruleId, RoomPricingUpsertRequest req) {
        validateWindow(req);
        RoomPricingRule rule = require(roomId, ruleId);
        rule.setName(req.name());
        rule.setHourlyRate(req.hourlyRate());
        rule.setCurrency(req.currency());
        rule.setDayOfWeek(req.dayOfWeek());
        rule.setStartTime(req.startTime());
        rule.setEndTime(req.endTime());
        rule.setValidFrom(req.validFrom());
        rule.setValidTo(req.validTo());
        if (req.priority() != null) rule.setPriority(req.priority());
        return toDto(rules.save(rule));
    }

    @Transactional
    public void delete(UUID roomId, UUID ruleId) {
        rules.delete(require(roomId, ruleId));
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
