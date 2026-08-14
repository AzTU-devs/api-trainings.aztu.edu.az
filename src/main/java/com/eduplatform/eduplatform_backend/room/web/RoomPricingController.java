package com.eduplatform.eduplatform_backend.room.web;

import com.eduplatform.eduplatform_backend.common.web.ApiResponse;
import com.eduplatform.eduplatform_backend.room.service.RoomPricingService;
import com.eduplatform.eduplatform_backend.room.web.dto.RoomPricingRuleDto;
import com.eduplatform.eduplatform_backend.room.web.dto.RoomPricingUpsertRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/rooms/{roomId}/pricing-rules")
@Tag(name = "Admin — Room pricing")
@PreAuthorize("hasAuthority('room:manage')")
public class RoomPricingController {

    private final RoomPricingService service;

    public RoomPricingController(RoomPricingService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "List a room's pricing rules (highest priority first)")
    public ApiResponse<List<RoomPricingRuleDto>> list(@PathVariable UUID roomId) {
        return ApiResponse.ok(service.list(roomId));
    }

    @PostMapping
    @Operation(summary = "Add a pricing rule to a room")
    public ResponseEntity<ApiResponse<RoomPricingRuleDto>> create(@PathVariable UUID roomId,
                                                                  @Valid @RequestBody RoomPricingUpsertRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(service.create(roomId, req)));
    }

    @PutMapping("/{ruleId}")
    @Operation(summary = "Update a room pricing rule")
    public ApiResponse<RoomPricingRuleDto> update(@PathVariable UUID roomId, @PathVariable UUID ruleId,
                                                  @Valid @RequestBody RoomPricingUpsertRequest req) {
        return ApiResponse.ok(service.update(roomId, ruleId, req));
    }

    @DeleteMapping("/{ruleId}")
    @Operation(summary = "Delete a room pricing rule")
    public ResponseEntity<Void> delete(@PathVariable UUID roomId, @PathVariable UUID ruleId) {
        service.delete(roomId, ruleId);
        return ResponseEntity.noContent().build();
    }
}
